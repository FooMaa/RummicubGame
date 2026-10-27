package com.rummiclub.core;

import java.util.*;

/** Bounded search, using only the current rack and public table. */
public final class Bot {
    private Bot() { }
    public static List<List<Integer>> choose(List<Integer> rack, List<List<Integer>> table, boolean opened) {
        List<List<Integer>> candidates = candidates(rack);
        candidates.sort((a,b) -> Integer.compare(b.size() * 40 + Rules.meldValue(b), a.size() * 40 + Rules.meldValue(a)));
        Search search = new Search(candidates);
        search.visit(0, new HashSet<>(), new ArrayList<>(), 0);
        List<List<Integer>> result = Rules.copyBoard(table);
        Set<Integer> used = new HashSet<>();
        for (List<Integer> meld : search.best) { result.add(new ArrayList<>(meld)); used.addAll(meld); }
        if (opened) {
            // Extend either end of runs, fill groups, and insert a tile next to jokers.
            boolean changed;
            do {
                changed = false;
                outer: for (int id : rack) if (!used.contains(id)) {
                    for (int m = 0; m < result.size(); m++) for (int at = 0; at <= result.get(m).size(); at++) {
                        List<Integer> trial = new ArrayList<>(result.get(m)); trial.add(at, id);
                        if (Rules.meldValue(trial) >= 0) { result.set(m, trial); used.add(id); changed = true; break outer; }
                    }
                }
            } while (changed);
        }
        try { Rules.validateMove(table, rack, opened, result); return result; }
        catch (IllegalArgumentException noMove) { return null; }
    }
    public static void turn(Game g, long now) {
        Game.Player p = g.current();
        List<List<Integer>> choice = choose(p.rack, g.board, p.opened);
        if (choice == null) g.draw(p.id, g.revision, now); else g.play(p.id, g.revision, choice, now);
    }
    static List<List<Integer>> candidates(List<Integer> rack) {
        List<List<Integer>> out = new ArrayList<>();
        List<Integer> jokers = new ArrayList<>();
        for (int id : rack) if (Rules.joker(id)) jokers.add(id);
        for (int n = 1; n <= 13; n++) {
            List<Integer> colors = new ArrayList<>();
            for (int c = 0; c < 4; c++) { int id = find(rack, c, n); if (id >= 0) colors.add(id); }
            for (int mask = 1; mask < (1 << colors.size()); mask++) {
                List<Integer> group = new ArrayList<>();
                for (int j = 0; j < colors.size(); j++) if ((mask & (1 << j)) != 0) group.add(colors.get(j));
                for (int j = 0; j <= jokers.size(); j++) {
                    List<Integer> candidate = new ArrayList<>(group); candidate.addAll(jokers.subList(0, j));
                    if (Rules.meldValue(candidate) >= 0) out.add(candidate);
                }
            }
        }
        for (int c = 0; c < 4; c++) for (int start = 1; start <= 11; start++) for (int end = start + 2; end <= 13; end++) {
            List<Integer> run = new ArrayList<>(); int j = 0;
            for (int n = start; n <= end; n++) {
                int id = find(rack, c, n);
                if (id >= 0) run.add(id); else if (j < jokers.size()) run.add(jokers.get(j++)); else { run.clear(); break; }
            }
            if (Rules.meldValue(run) >= 0) out.add(run);
        }
        return out;
    }
    private static int find(List<Integer> rack, int c, int n) {
        for (int id : rack) if (!Rules.joker(id) && Rules.color(id) == c && Rules.number(id) == n) return id;
        return -1;
    }
    private static final class Search {
        final List<List<Integer>> all;
        List<List<Integer>> best = new ArrayList<>();
        int bestScore = -1, nodes;
        Search(List<List<Integer>> all) { this.all = all; }
        void visit(int index, Set<Integer> used, List<List<Integer>> chosen, int points) {
            if (++nodes > 18000) return;
            int score = (points >= 30 ? 10000 : 0) + used.size() * 40 + points;
            if (score > bestScore) { bestScore = score; best = new ArrayList<>(chosen); }
            for (int i = index; i < all.size(); i++) {
                List<Integer> meld = all.get(i);
                if (!Collections.disjoint(used, meld)) continue;
                used.addAll(meld); chosen.add(meld);
                visit(i + 1, used, chosen, points + Rules.meldValue(meld));
                chosen.remove(chosen.size() - 1); used.removeAll(meld);
                if (nodes > 18000) break;
            }
        }
    }
}
