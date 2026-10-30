package com.rummiclub.core;

import java.util.*;

/** Serializable authoritative state. Never send this object to another player. */
public final class Game {
    public List<Player> players = new ArrayList<>();
    public List<List<Integer>> board = new ArrayList<>();
    public List<Integer> pool = new ArrayList<>();
    public int turn;
    public long revision;
    public long deadline;
    public int turnSeconds;
    public int passes;
    public boolean finished;
    public boolean editing;
    public List<List<Integer>> pendingBoard;
    public long pendingVersion;
    public String winner;
    public String message = "Партия началась";

    public static final class Player {
        public String id;
        public String name;
        public boolean bot;
        public boolean opened;
        public int score;
        public List<Integer> rack = new ArrayList<>();
        public Player() { }
        public Player(String id, String name, boolean bot) { this.id = id; this.name = name; this.bot = bot; }
    }

    public static Game start(List<Player> players, Random random, int turnSeconds, long now) {
        if (players.size() < 2 || players.size() > 4) throw new IllegalArgumentException("Нужно от 2 до 4 игроков");
        Game g = new Game();
        Set<String> ids = new HashSet<>();
        for (Player p : players) {
            if (!ids.add(p.id)) throw new IllegalArgumentException("Повтор игрока");
            g.players.add(new Player(p.id, p.name, p.bot));
        }
        for (int i = 0; i < 106; i++) g.pool.add(i);
        Collections.shuffle(g.pool, random);
        for (Player p : g.players) for (int i = 0; i < 14; i++) p.rack.add(g.pool.remove(g.pool.size() - 1));
        g.turn = random.nextInt(players.size());
        g.turnSeconds = turnSeconds;
        g.setDeadline(now);
        return g;
    }

    public Player current() { return players.get(turn); }
    public Player player(String id) {
        for (Player p : players) if (p.id.equals(id)) return p;
        throw new IllegalArgumentException("Вы не участвуете в партии");
    }
    public void checkTurn(String id, long expectedRevision) {
        if (finished) throw new IllegalArgumentException("Партия завершена");
        if (expectedRevision != revision) throw new IllegalArgumentException("Состояние изменилось. Обновите стол");
        if (!current().id.equals(id)) throw new IllegalArgumentException("Сейчас ход другого игрока");
    }
    public void play(String id, long expectedRevision, List<List<Integer>> proposed, long now) {
        checkTurn(id, expectedRevision);
        Player p = current();
        Rules.validateMove(board, p.rack, p.opened, proposed);
        Set<Integer> played = new HashSet<>();
        for (List<Integer> meld : proposed) played.addAll(meld);
        int count = p.rack.size();
        p.rack.removeIf(played::contains);
        board = Rules.copyBoard(proposed);
        p.opened = true;
        passes = 0;
        message = p.name + " выкладывает " + (count - p.rack.size()) + " фишек";
        if (p.rack.isEmpty()) finish(p, false); else next(now);
    }
    public void draw(String id, long expectedRevision, long now) { checkTurn(id, expectedRevision); drawTiles(1, now); }
    public void timeout(long now) {
        if (!finished && deadline > 0 && now >= deadline) {
            List<List<Integer>> resolved=pendingBoard==null?Rules.copyBoard(board):TimeoutResolver.resolve(board,current().rack,current().opened,pendingBoard);
            if(!Rules.flatten(board).equals(Rules.flatten(resolved)))play(current().id,revision,resolved,now);
            else drawTiles(1,now);
        }
    }
    public void stage(String id,long expectedRevision,List<List<Integer>> proposed) {
        stage(id,expectedRevision,pendingVersion+1,proposed);
    }
    public void stage(String id,long expectedRevision,long version,List<List<Integer>> proposed) {
        checkTurn(id,expectedRevision);TimeoutResolver.validateDraft(board,current().rack,proposed);
        if(version<=pendingVersion)return;
        pendingBoard=Rules.copyBoard(proposed);pendingVersion=version;editing=!pendingBoard.equals(board);
    }
    private void drawTiles(int amount, long now) {
        Player p = current();
        boolean empty = pool.isEmpty();
        int drawn = 0;
        while (drawn < amount && !pool.isEmpty()) { p.rack.add(pool.remove(pool.size() - 1)); drawn++; }
        message = p.name + (drawn == 0 ? " пропускает ход" : " берёт фишки: " + drawn);
        passes = empty ? passes + 1 : 0;
        if (passes >= players.size()) {
            Player best = players.get(0);
            for (Player candidate : players) if (Rules.rackValue(candidate.rack) < Rules.rackValue(best.rack)) best = candidate;
            finish(best, true);
        } else next(now);
    }
    private void next(long now) {
        turn = (turn + 1) % players.size(); revision++; editing = false; pendingBoard=null;pendingVersion=0; setDeadline(now);
    }
    private void setDeadline(long now) { deadline = turnSeconds == 0 ? 0 : now + turnSeconds * 1000L; }
    private void finish(Player p, boolean blocked) {
        finished = true; winner = p.id; revision++; deadline = 0; editing = false;pendingBoard=null;pendingVersion=0;
        int base = blocked ? Rules.rackValue(p.rack) : 0;
        for (Player other : players) {
            if (other == p) continue;
            other.score = base - Rules.rackValue(other.rack); p.score -= other.score;
        }
        message = blocked ? "Банк пуст, круг без ходов. Победа по очкам: " + p.name : "Руммикуб! Победил " + p.name;
    }
}
