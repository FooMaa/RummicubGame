package com.rummiclub.core;

import java.util.*;

public final class Rules {
    private Rules() { }
    public static boolean joker(int id) { return id >= 104; }
    public static int color(int id) { return id / 26; }
    public static int number(int id) { return id % 13 + 1; }
    public static int rackValue(List<Integer> rack) {
        int sum = 0; for (int id : rack) sum += joker(id) ? 30 : number(id); return sum;
    }
    /** Runs are ordered; a joker's position determines its value. */
    public static int meldValue(List<Integer> meld) {
        if (meld == null || meld.size() < 3 || meld.size() > 13) return -1;
        Set<Integer> unique = new HashSet<>();
        int value = -1, color = -1, start = -1;
        boolean group = meld.size() <= 4, run = true;
        Set<Integer> colors = new HashSet<>();
        for (int i = 0; i < meld.size(); i++) {
            Integer id = meld.get(i);
            if (id == null || id < 0 || id > 105 || !unique.add(id)) return -1;
            if (joker(id)) continue;
            int n = number(id), c = color(id);
            if (value != -1 && n != value) group = false;
            if (!colors.add(c)) group = false;
            value = n;
            if (color != -1 && (c != color || n - i != start)) run = false;
            color = c; start = n - i;
        }
        if (value == -1) return -1;
        if (group) return value * meld.size();
        if (run && start >= 1 && start + meld.size() - 1 <= 13)
            return (2 * start + meld.size() - 1) * meld.size() / 2;
        return -1;
    }
    public static void validateMove(List<List<Integer>> before, List<Integer> rack, boolean opened, List<List<Integer>> after) {
        if (after == null || after.size() > 35) fail("Некорректный стол");
        Set<Integer> old = flatten(before), owned = new HashSet<>(rack), used = new HashSet<>();
        int points = 0;
        for (List<Integer> meld : after) {
            int value = meldValue(meld);
            if (value < 0) fail("Каждый ряд: группа 3–4 разных цветов или последовательность от 3 фишек");
            for (int tile : meld) {
                if (!used.add(tile)) fail("Фишка использована дважды");
                if (!old.contains(tile) && !owned.contains(tile)) fail("Этой фишки нет у игрока");
            }
            if (Collections.disjoint(old, meld)) points += value;
        }
        if (!used.containsAll(old)) fail("Все фишки стола должны остаться на столе");
        if (used.size() <= old.size()) fail("Выложите хотя бы одну свою фишку или возьмите из банка");
        if (!opened) {
            for (List<Integer> meld : before) if (!after.contains(meld)) fail("До первого выхода нельзя менять стол");
            if (points < 30) fail("Первый выход — минимум 30 очков из своих фишек. Сейчас: " + points);
        }
    }
    public static Set<Integer> flatten(List<List<Integer>> board) {
        Set<Integer> result = new HashSet<>(); for (List<Integer> m : board) result.addAll(m); return result;
    }
    public static List<List<Integer>> copyBoard(List<List<Integer>> board) {
        List<List<Integer>> out = new ArrayList<>(); for (List<Integer> m : board) out.add(new ArrayList<>(m)); return out;
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
