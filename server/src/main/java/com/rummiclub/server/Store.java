package com.rummiclub.server;

import com.google.gson.Gson;
import com.rummiclub.core.Room;
import java.nio.file.*;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.sql.*;
import java.util.*;

public final class Store implements AutoCloseable {
    private final Connection db;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson JSON = new Gson();
    private static final int ITERATIONS = 210_000;

    public Store(Path path) throws Exception {
        if (path.toAbsolutePath().getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, login TEXT UNIQUE NOT NULL, name TEXT NOT NULL, salt TEXT NOT NULL, hash TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS sessions (token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, expires INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rooms (code TEXT PRIMARY KEY, state TEXT NOT NULL)");
        }
    }
    public static final class Account {
        public String id, name, token;
        Account(String id, String name, String token) { this.id = id; this.name = name; this.token = token; }
    }
    public synchronized Account register(String name, String password) throws Exception {
        String login = name.trim().toLowerCase(Locale.ROOT);
        if (!name.trim().matches("[\\p{L}\\p{N}_ -]{3,24}")) throw new IllegalArgumentException("Имя: 3–24 буквы, цифры, пробел, _ или -");
        if (password.length() < 8 || password.length() > 128) throw new IllegalArgumentException("Пароль: от 8 до 128 символов");
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        String id = UUID.randomUUID().toString();
        try (PreparedStatement p = db.prepareStatement("INSERT INTO users VALUES (?, ?, ?, ?, ?)")) {
            p.setString(1, id); p.setString(2, login); p.setString(3, name.trim());
            p.setString(4, Base64.getEncoder().encodeToString(salt)); p.setString(5, hashPassword(password, salt));
            try { p.executeUpdate(); } catch (SQLException duplicate) {
                if (duplicate.getErrorCode() == 19) throw new IllegalArgumentException("Имя уже занято");
                throw duplicate;
            }
        }
        return session(id, name.trim());
    }
    public synchronized Account login(String name, String password) throws Exception {
        if (password.length() > 128 || name.length() > 24) throw new IllegalArgumentException("Неверное имя или пароль");
        try (PreparedStatement p = db.prepareStatement("SELECT * FROM users WHERE login=?")) {
            p.setString(1, name.trim().toLowerCase(Locale.ROOT));
            try (ResultSet r = p.executeQuery()) {
                boolean found = r.next();
                byte[] salt = found ? Base64.getDecoder().decode(r.getString("salt")) : new byte[16];
                String calculated = hashPassword(password, salt);
                if (!found || !MessageDigest.isEqual(calculated.getBytes(java.nio.charset.StandardCharsets.UTF_8), r.getString("hash").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    throw new IllegalArgumentException("Неверное имя или пароль");
                return session(r.getString("id"), r.getString("name"));
            }
        }
    }
    private Account session(String id, String name) throws Exception {
        String token = randomToken();
        try (PreparedStatement p = db.prepareStatement("INSERT INTO sessions VALUES (?, ?, ?)")) {
            p.setString(1, digest(token)); p.setString(2, id); p.setLong(3, System.currentTimeMillis() + 30L * 86400000); p.executeUpdate();
        }
        try (PreparedStatement p = db.prepareStatement("DELETE FROM sessions WHERE expires < ?")) { p.setLong(1, System.currentTimeMillis()); p.executeUpdate(); }
        return new Account(id, name, token);
    }
    public synchronized Account authenticate(String token) throws Exception {
        if (token == null || token.length() > 100) throw new SecurityException("Войдите в аккаунт");
        try (PreparedStatement p = db.prepareStatement("SELECT users.id, users.name FROM sessions JOIN users ON users.id=sessions.user_id WHERE token_hash=? AND expires>?")) {
            p.setString(1, digest(token)); p.setLong(2, System.currentTimeMillis());
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new SecurityException("Сессия истекла. Войдите снова");
                return new Account(r.getString(1), r.getString(2), null);
            }
        }
    }
    public synchronized void logout(String token) throws Exception {
        try (PreparedStatement p = db.prepareStatement("DELETE FROM sessions WHERE token_hash=?")) { p.setString(1, digest(token)); p.executeUpdate(); }
    }
    public synchronized Map<String, Room> loadRooms() throws SQLException {
        Map<String, Room> rooms = new HashMap<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT code,state FROM rooms")) {
            while (r.next()) rooms.put(r.getString(1), JSON.fromJson(r.getString(2), Room.class));
        }
        return rooms;
    }
    public synchronized void save(Room room) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT OR REPLACE INTO rooms VALUES (?, ?)")) {
            p.setString(1, room.code); p.setString(2, JSON.toJson(room)); p.executeUpdate();
        }
    }
    public synchronized void delete(String code) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("DELETE FROM rooms WHERE code=?")) { p.setString(1, code); p.executeUpdate(); }
    }
    private static String hashPassword(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try { return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        finally { spec.clearPassword(); }
    }
    private static String randomToken() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String digest(String token) throws Exception { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
