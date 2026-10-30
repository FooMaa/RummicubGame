package com.rummiclub.core;

import java.util.*;

/** Shared insertion semantics for taps and drag/drop. Inputs are not mutated. */
public final class DraftBoard {
    private DraftBoard() { }
    public static List<List<Integer>> move(List<List<Integer>> board, Collection<Integer> tiles, int row, Integer anchor, boolean after) {
        if(tiles.isEmpty() || new HashSet<>(tiles).size()!=tiles.size())throw new IllegalArgumentException("Выберите фишки");
        List<List<Integer>> result=Rules.copyBoard(board);
        if(row< -1 || row>=result.size())throw new IllegalArgumentException("Ряд изменился");
        if(anchor!=null && tiles.contains(anchor))throw new IllegalArgumentException("Точка вставки входит в выбор");
        List<Integer> target=row<0?new ArrayList<>():result.get(row);
        if(anchor!=null&&!target.contains(anchor))throw new IllegalArgumentException("Точка вставки исчезла");
        for(List<Integer> meld:result)meld.removeAll(tiles);
        int at=anchor==null?(after?target.size():0):target.indexOf(anchor)+(after?1:0);
        if(target.size()+tiles.size()>13)throw new IllegalArgumentException("В ряду не больше 13 фишек");
        target.addAll(at,tiles);if(row<0)result.add(target);result.removeIf(List::isEmpty);
        return result;
    }
}
