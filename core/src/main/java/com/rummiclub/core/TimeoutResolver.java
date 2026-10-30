package com.rummiclub.core;

import java.util.*;

/** Salvages valid additions without losing or returning any previously committed table tile. */
public final class TimeoutResolver {
    private TimeoutResolver() { }
    public static void validateDraft(List<List<Integer>> before, List<Integer> rack, List<List<Integer>> draft) {
        if (draft == null || draft.size() > 35) throw new IllegalArgumentException("Слишком много рядов");
        Set<Integer> allowed = Rules.flatten(before); allowed.addAll(rack);
        Set<Integer> seen = new HashSet<>();
        for (List<Integer> row : draft) {
            if (row == null || row.size() > 13) throw new IllegalArgumentException("В ряду не больше 13 фишек");
            for (Integer tile : row) if (tile == null || !allowed.contains(tile) || !seen.add(tile))
                throw new IllegalArgumentException("Повторная или чужая фишка");
        }
        if (!seen.containsAll(Rules.flatten(before))) throw new IllegalArgumentException("Фишки общего стола нельзя забирать на подставку");
    }
    public static List<List<Integer>> resolve(List<List<Integer>> before, List<Integer> rack, boolean opened, List<List<Integer>> draft) {
        List<List<Integer>> unchanged = Rules.copyBoard(before);
        try { validateDraft(before, rack, draft); } catch (IllegalArgumentException invalid) { return unchanged; }
        Set<Integer> old = Rules.flatten(before);
        Map<Integer,Integer> origins = new HashMap<>();
        int[] parent = new int[before.size()];
        for (int i=0;i<before.size();i++) { parent[i]=i; for (int tile:before.get(i)) origins.put(tile,i); }
        for (List<Integer> row:draft) {
            Integer first=null;
            for (int tile:row) if (origins.containsKey(tile)) {
                int origin=origins.get(tile); if(first==null)first=origin; else parent[root(parent,origin)]=root(parent,first);
            }
        }
        List<List<Integer>> result = new ArrayList<>(), candidates = new ArrayList<>();
        Set<Integer> broken = new HashSet<>();
        for (List<Integer> row:draft) {
            List<Integer> candidate = best(row, old);
            Integer component=null;
            for(int tile:row)if(origins.containsKey(tile)){component=root(parent,origins.get(tile));break;}
            if (component==null) { if(candidate!=null)result.add(candidate); }
            else {
                if (!opened || candidate==null) broken.add(component);
                candidates.add(candidate==null?new ArrayList<>(row):candidate);
            }
        }
        for (int component:broken) for(int i=0;i<before.size();i++) if(root(parent,i)==component)result.add(new ArrayList<>(before.get(i)));
        for (List<Integer> row:candidates) {
            int component=-1; for(int tile:row)if(origins.containsKey(tile)){component=root(parent,origins.get(tile));break;}
            if(!broken.contains(component))result.add(row);
        }
        try { Rules.validateMove(before,rack,opened,result); return result; }
        catch (IllegalArgumentException invalid) { return unchanged; }
    }
    private static int root(int[] parent,int i) { while(parent[i]!=i){parent[i]=parent[parent[i]];i=parent[i];}return i; }
    /** Keep every old tile and the largest valid ordered subset of the newly placed tiles. */
    private static List<Integer> best(List<Integer> row,Set<Integer> old) {
        if (Rules.meldValue(row)>=0) return new ArrayList<>(row);
        List<Integer> optional = new ArrayList<>();for(int tile:row)if(!old.contains(tile))optional.add(tile);
        List<Integer> best=null;
        for(int mask=0;mask<(1<<optional.size());mask++) {
            int size=row.size()-optional.size()+Integer.bitCount(mask);
            if(size<3 || (best!=null&&size<=best.size()))continue;
            List<Integer> candidate=new ArrayList<>();int bit=0;
            for(int tile:row) { if(old.contains(tile))candidate.add(tile);else if((mask&(1<<bit++))!=0)candidate.add(tile); }
            if(Rules.meldValue(candidate)>=0)best=candidate;
        }
        return best;
    }
}
