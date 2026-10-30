package com.rummiclub.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TimeoutTest {
    static List<Integer> row(Integer... ids){return new ArrayList<>(Arrays.asList(ids));}
    @SafeVarargs static List<List<Integer>> board(List<Integer>... rows){return new ArrayList<>(Arrays.asList(rows));}
    static Game game(List<List<Integer>> before,List<Integer> rack,boolean opened){
        Game g=new Game();Game.Player a=new Game.Player("a","A",false),b=new Game.Player("b","B",false);
        a.rack=new ArrayList<>(rack);a.opened=opened;b.rack=row(90,91,92);g.players.add(a);g.players.add(b);g.board=Rules.copyBoard(before);g.deadline=1000;g.turnSeconds=60;
        Set<Integer> used=Rules.flatten(before);used.addAll(rack);used.addAll(b.rack);for(int i=0;i<106;i++)if(!used.contains(i))g.pool.add(i);return g;
    }
    static void conserved(Game g){List<Integer> all=new ArrayList<>(g.pool);for(Game.Player p:g.players)all.addAll(p.rack);for(List<Integer> r:g.board){assertTrue(Rules.meldValue(r)>=0);all.addAll(r);}assertEquals(106,all.size());assertEquals(106,new HashSet<>(all).size());}
    @Test public void keepsValidExtensionAndReturnsOnlyBadNewTiles(){
        Game g=game(board(row(0,1,2)),row(3,40,52),true);
        g.stage("a",0,board(row(0,1,2,40,3),row(52)));g.timeout(1000);
        assertEquals(board(row(0,1,2,3)),g.board);assertEquals(row(40,52),g.player("a").rack);assertEquals(1,g.revision);assertNull(g.pendingBoard);conserved(g);
        g.timeout(1000);assertEquals(1,g.revision);
    }
    @Test public void brokenRearrangementRestoresOnlyDependentOriginalRows(){
        Game g=game(board(row(0,1,2,3,4,5),row(26,27,28)),row(6,29,52),true);
        g.stage("a",0,board(row(0,1,2,3),row(4,5),row(26,27,28,29),row(52)));g.timeout(1000);
        assertTrue(g.board.contains(row(0,1,2,3,4,5)));assertTrue(g.board.contains(row(26,27,28,29)));assertEquals(row(6,52),g.player("a").rack);conserved(g);
    }
    @Test public void openingThresholdAndWinAreStillEnforced(){
        Game g=game(board(),row(0,1,2),false);g.stage("a",0,board(row(0,1,2)));g.timeout(1000);
        assertTrue(g.board.isEmpty());assertEquals(4,g.player("a").rack.size());assertFalse(g.player("a").opened);conserved(g);
        g=game(board(),row(9,35,61,80),false);g.stage("a",0,board(row(9,35,61),row(80)));g.timeout(1000);
        assertTrue(g.player("a").opened);assertEquals(row(80),g.player("a").rack);conserved(g);
        g=game(board(),row(9,35,61),false);g.stage("a",0,board(row(9,35,61)));g.timeout(1000);assertTrue(g.finished);assertEquals("a",g.winner);conserved(g);
    }
    @Test public void draftCannotStealDuplicateOrEraseTableAndOldVersionsCannotUndoReset(){
        Game g=game(board(row(0,1,2)),row(3),true);
        assertThrows(IllegalArgumentException.class,()->g.stage("a",0,board(row(0,1,2,4))));
        assertThrows(IllegalArgumentException.class,()->g.stage("a",0,board(row(1,2,3))));
        assertThrows(IllegalArgumentException.class,()->g.stage("a",0,board(row(0,1,2,3,3))));
        g.stage("a",0,1,board(row(0,1,2,3)));g.stage("a",0,3,g.board);g.stage("a",0,2,board(row(0,1,2,3)));
        assertEquals(g.board,g.pendingBoard);g.timeout(1000);assertEquals(2,g.player("a").rack.size());conserved(g);
    }
    @Test public void privateDraftIsVisibleOnlyToItsOwnerAndCannotAlterFirstOpeningTable(){
        Game g=game(board(row(0,1,2)),row(3,9,35,61),false);g.stage("a",0,board(row(0,1,2,3),row(9,35,61)));
        Room r=new Room();r.game=g;r.members=g.players;
        assertNotNull(r.view("a").draft);assertNull(r.view("b").draft);
        g.timeout(1000);assertTrue(g.board.contains(row(0,1,2)));assertEquals(row(3),g.player("a").rack);conserved(g);
    }
    @Test public void insertionBeforeAfterMiddleAndMoveWithinRowPreserveTiles(){
        List<List<Integer>> original=board(row(0,1,2),row(26,27,28));
        assertEquals(row(3,0,1,2),DraftBoard.move(original,row(3),0,null,false).get(0));
        assertEquals(row(0,3,1,2),DraftBoard.move(original,row(3),0,1,false).get(0));
        assertEquals(row(0,1,3,2),DraftBoard.move(original,row(3),0,1,true).get(0));
        assertEquals(row(1,2,0),DraftBoard.move(original,row(0),0,null,true).get(0));
        assertEquals(row(0,1,2),original.get(0));
        assertThrows(IllegalArgumentException.class,()->DraftBoard.move(original,row(1),0,1,false));
    }
    @Test public void mixedIncompleteDraftsAlwaysLeaveAValidConservedGame(){
        for(int seed=0;seed<80;seed++){
            Game g=game(board(row(0,1,2),row(26,27,28)),row(3,4,29,30,52,53,54,55,104),true);
            List<Integer> tiles=new ArrayList<>(Rules.flatten(g.board));tiles.addAll(g.current().rack);Collections.shuffle(tiles,new Random(seed));
            List<List<Integer>> proposed=new ArrayList<>();for(int i=0;i<tiles.size();i+=5)proposed.add(new ArrayList<>(tiles.subList(i,Math.min(i+5,tiles.size()))));
            g.stage("a",0,proposed);g.timeout(1000);conserved(g);assertEquals(1,g.revision);
        }
    }
}
