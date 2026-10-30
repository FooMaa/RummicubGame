package com.rummiclub.core;

import java.util.*;

public final class Room {
    public String code;
    public String host;
    public List<Game.Player> members = new ArrayList<>();
    public Game game;
    public int turnSeconds = 60;
    public long updatedAt;
    public long botAt;

    public View view(String viewer) {
        if (members.stream().noneMatch(p -> p.id.equals(viewer))) throw new IllegalArgumentException("Нет доступа к комнате");
        View v = new View(); v.code = code; v.host = host; v.me = viewer; v.turnSeconds = turnSeconds;
        for (Game.Player p : game == null ? members : game.players) {
            Seat seat = new Seat(); seat.id = p.id; seat.name = p.name; seat.bot = p.bot;
            seat.opened = p.opened; seat.count = p.rack.size(); seat.score = p.score; v.players.add(seat);
        }
        if (game != null) {
            v.started = true; v.board = Rules.copyBoard(game.board); v.rack = new ArrayList<>(game.player(viewer).rack);
            v.turn = game.current().id; v.revision = game.revision; v.deadline = game.deadline;
            v.poolCount = game.pool.size(); v.finished = game.finished; v.winner = game.winner; v.message = game.message;
            if(viewer.equals(game.current().id)) { v.draft=game.pendingBoard==null?null:Rules.copyBoard(game.pendingBoard);v.draftVersion=game.pendingVersion; }
        }
        return v;
    }
    public static final class Seat {
        public String id, name;
        public boolean bot, opened;
        public int count, score;
    }
    /** Deliberately excludes pool contents, opponents' racks and account data. */
    public static final class View {
        public String code, host, me, turn, winner, message;
        public boolean started, finished;
        public long revision, deadline;
        public long draftVersion;
        public List<List<Integer>> draft;
        public int poolCount, turnSeconds;
        public List<Seat> players = new ArrayList<>();
        public List<Integer> rack = new ArrayList<>();
        public List<List<Integer>> board = new ArrayList<>();
    }
}
