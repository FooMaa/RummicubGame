package com.rummiclub.server;

import com.google.gson.*;
import com.rummiclub.core.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public final class Server implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final HttpServer http;
    private final Store store;
    private final Map<String, Room> rooms;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.CallerRunsPolicy());
    private final Map<String, long[]> attempts = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    public Server(int port, Path database) throws Exception {
        store = new Store(database); rooms = store.loadRooms();
        http = HttpServer.create(new InetSocketAddress(port), 64);
        http.createContext("/", this::handle); http.setExecutor(workers);
    }
    public void start() { http.start(); scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS); }
    public int port() { return http.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        int status = 200; Object result;
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) result = Map.of("status", "ok");
            else {
                if (!exchange.getRequestMethod().equals("POST")) { respond(exchange, 405, Map.of("error", "Используйте POST")); return; }
                byte[] bytes = exchange.getRequestBody().readNBytes(65537);
                if (bytes.length > 65536) { respond(exchange, 413, Map.of("error", "Запрос слишком большой")); return; }
                JsonObject body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (path.equals("/api/auth/register") || path.equals("/api/auth/login")) {
                    if (!allow(exchange.getRemoteAddress().getAddress().getHostAddress())) { respond(exchange, 429, Map.of("error", "Слишком много попыток. Подождите 15 минут")); return; }
                    String name = string(body, "name"), password = string(body, "password");
                    result = path.endsWith("register") ? store.register(name, password) : store.login(name, password);
                } else {
                    String header = exchange.getRequestHeaders().getFirst("Authorization");
                    String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
                    Store.Account account = store.authenticate(token);
                    if (path.equals("/api/auth/logout")) { store.logout(token); result = Map.of("ok", true); }
                    else if (path.startsWith("/api/room/")) result = roomAction(path.substring(10), body, account);
                    else { respond(exchange, 404, Map.of("error", "Маршрут не найден")); return; }
                }
            }
        } catch (SecurityException e) { status = 401; result = Map.of("error", e.getMessage()); }
        catch (JsonParseException e) { status = 400; result = Map.of("error", "Некорректный JSON"); }
        catch (IllegalArgumentException | IllegalStateException | NullPointerException e) { status = 400; result = Map.of("error", e.getMessage() == null ? "Некорректный запрос" : e.getMessage()); }
        catch (Exception e) { status = 500; result = Map.of("error", "Ошибка сервера. Попробуйте ещё раз"); System.err.println("Request failed: " + e.getClass().getSimpleName()); }
        respond(exchange, status, result);
    }
    private static String string(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Не задано поле: " + key);
        return object.get(key).getAsString();
    }
    private synchronized boolean allow(String ip) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> e.getValue()[0] < now - 900000);
        if (attempts.size() > 10000) return false;
        long[] count = attempts.computeIfAbsent(ip, key -> new long[]{now, 0});
        return ++count[1] <= 30;
    }
    private synchronized Object roomAction(String action, JsonObject body, Store.Account user) throws Exception {
        long now = System.currentTimeMillis();
        if (action.equals("current")) {
            for (Room r : rooms.values()) if (member(r, user.id)) return r.view(user.id);
            return Map.of("code", "");
        }
        if (action.equals("create")) {
            ensureNoRoom(user.id, "");
            if (rooms.size() >= 1000) throw new IllegalArgumentException("Сервер заполнен");
            Room r = new Room();
            do { r.code = code(); } while (rooms.containsKey(r.code));
            r.host = user.id; r.members.add(new Game.Player(user.id, user.name, false));
            int seconds = body.has("turnSeconds") ? body.get("turnSeconds").getAsInt() : 60;
            if (seconds != 0 && seconds != 60 && seconds != 120) throw new IllegalArgumentException("Таймер: 0, 60 или 120 секунд");
            r.turnSeconds = seconds; save(r, now); return r.view(user.id);
        }
        String code = string(body, "code").trim().toUpperCase(Locale.ROOT);
        Room existing = rooms.get(code);
        if (existing == null) throw new IllegalArgumentException("Комната не найдена");
        Room r = JSON.fromJson(JSON.toJson(existing), Room.class); // mutate a copy, publish only after durable save
        if (action.equals("join") && !member(r, user.id)) {
            ensureNoRoom(user.id, r.code);
            if (r.game != null) throw new IllegalArgumentException("Партия уже идёт");
            if (r.members.size() >= 4) throw new IllegalArgumentException("В комнате уже 4 игрока");
            r.members.add(new Game.Player(user.id, user.name, false)); save(r, now); return r.view(user.id);
        }
        if (!member(r, user.id)) throw new IllegalArgumentException("Нет доступа к комнате");
        if (action.equals("state") || action.equals("join")) return r.view(user.id);
        switch (action) {
            case "bot":
                host(r, user.id); waiting(r);
                if (r.members.size() >= 4) throw new IllegalArgumentException("В комнате уже 4 игрока");
                r.members.add(new Game.Player("bot-" + UUID.randomUUID(), "Бот " + r.members.size(), true)); break;
            case "removeBot":
                host(r, user.id); waiting(r);
                r.members.removeIf(p -> p.bot && p.id.equals(string(body, "player"))); break;
            case "start":
                host(r, user.id); waiting(r);
                r.game = Game.start(r.members, random, r.turnSeconds, now); break;
            case "rematch":
                host(r, user.id);
                if (r.game == null || !r.game.finished) throw new IllegalArgumentException("Дождитесь конца партии");
                r.game = null; break;
            case "leave":
                r.members.removeIf(p -> p.id.equals(user.id));
                if (r.game != null && !r.game.finished) { Game.Player p = r.game.player(user.id); p.bot = true; p.name += " · бот"; }
                Optional<Game.Player> successor = r.members.stream().filter(p -> !p.bot).findFirst();
                if (successor.isEmpty()) { store.delete(r.code); rooms.remove(r.code); return Map.of("ok", true); }
                if (r.host.equals(user.id)) r.host = successor.get().id;
                break;
            case "play": case "draw": case "edit":
                if (r.game == null) throw new IllegalArgumentException("Партия ещё не началась");
                long revision = body.get("revision").getAsLong();
                if (r.game.deadline > 0 && now >= r.game.deadline && !r.game.finished) {
                    r.game.timeout(now); save(r, now); throw new IllegalArgumentException("Время хода истекло");
                }
                r.game.checkTurn(user.id, revision);
                if (action.equals("draw")) r.game.draw(user.id, revision, now);
                else if (action.equals("edit")) r.game.editing = body.get("editing").getAsBoolean();
                else {
                    List<List<Integer>> proposed = parseBoard(body.get("board"));
                    r.game.play(user.id, revision, proposed, now);
                }
                break;
            default: throw new IllegalArgumentException("Неизвестное действие");
        }
        save(r, now);
        return action.equals("leave") ? Map.of("ok", true) : r.view(user.id);
    }
    private static List<List<Integer>> parseBoard(JsonElement input) {
        if (input == null || !input.isJsonArray() || input.getAsJsonArray().size() > 35) throw new IllegalArgumentException("Некорректный стол");
        List<List<Integer>> result = new ArrayList<>();
        for (JsonElement row : input.getAsJsonArray()) {
            if (!row.isJsonArray() || row.getAsJsonArray().size() > 13) throw new IllegalArgumentException("Некорректный ряд");
            List<Integer> meld = new ArrayList<>();
            for (JsonElement tile : row.getAsJsonArray()) {
                if (!tile.isJsonPrimitive() || !tile.getAsJsonPrimitive().isNumber() || !tile.getAsString().matches("[0-9]{1,3}")) throw new IllegalArgumentException("Некорректная фишка");
                meld.add(tile.getAsInt());
            }
            result.add(meld);
        }
        return result;
    }
    private void ensureNoRoom(String id, String except) {
        for (Room r : rooms.values()) if (!r.code.equals(except) && member(r, id)) throw new IllegalArgumentException("Сначала выйдите из текущей комнаты");
    }
    private static boolean member(Room r, String id) { return r.members.stream().anyMatch(p -> p.id.equals(id)); }
    private static void host(Room r, String id) { if (!r.host.equals(id)) throw new IllegalArgumentException("Доступно только хозяину комнаты"); }
    private static void waiting(Room r) { if (r.game != null) throw new IllegalArgumentException("Партия уже началась"); }
    private String code() { String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; StringBuilder b = new StringBuilder(); for (int i=0;i<6;i++) b.append(alphabet.charAt(random.nextInt(alphabet.length()))); return b.toString(); }
    private void save(Room r, long now) throws Exception { r.updatedAt = now; r.botAt = now + 1600; store.save(r); rooms.put(r.code, r); }
    private synchronized void tick() {
        long now = System.currentTimeMillis();
        for (Room existing : new ArrayList<>(rooms.values())) {
            try {
                if (now - existing.updatedAt > 7L * 86400000) { store.delete(existing.code); rooms.remove(existing.code); continue; }
                if (existing.game == null || existing.game.finished) continue;
                Room r = JSON.fromJson(JSON.toJson(existing), Room.class);
                long revision = r.game.revision;
                if (r.game.current().bot && now >= r.botAt) Bot.turn(r.game, now); else r.game.timeout(now);
                if (r.game.revision != revision) save(r, now);
            } catch (Exception e) { System.err.println("Room tick failed: " + e.getClass().getSimpleName()); }
        }
    }
    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] output = JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, output.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(output); }
        finally { exchange.close(); }
    }
    @Override public void close() throws Exception { http.stop(0); scheduler.shutdownNow(); workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS); store.close(); }
}
