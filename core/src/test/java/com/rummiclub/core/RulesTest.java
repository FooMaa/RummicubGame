package com.rummiclub.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RulesTest {
    private static int t(int color,int number){return color*26+number-1;}
    private static List<Integer> row(Integer... ids){return new ArrayList<>(Arrays.asList(ids));}
    private static List<List<Integer>> board(List<Integer>... rows){return new ArrayList<>(Arrays.asList(rows));}
    private static void invalid(Runnable action){assertThrows(IllegalArgumentException.class,action::run);}
    @Test public void groupsAndRuns(){
        assertEquals(21,Rules.meldValue(row(t(0,7),t(1,7),t(3,7))));
        assertEquals(28,Rules.meldValue(row(t(0,7),t(1,7),t(2,7),t(3,7))));
        assertEquals(-1,Rules.meldValue(row(t(0,7),t(0,7)+13,t(2,7))));
        assertEquals(33,Rules.meldValue(row(t(1,10),t(1,11),t(1,12))));
        assertEquals(-1,Rules.meldValue(row(t(1,12),t(1,13),t(1,1))));
        assertEquals(-1,Rules.meldValue(row(t(1,1),t(1,2),t(2,3))));
        assertEquals(-1,Rules.meldValue(row(-1,1,2)));
        assertEquals(-1,Rules.meldValue(row(104,105,106)));
    }
    @Test public void jokerPositionDefinesOpeningPoints(){
        assertEquals(30,Rules.meldValue(row(t(0,9),104,t(0,11))));
        assertEquals(6,Rules.meldValue(row(104,t(0,2),105)));
        assertEquals(-1,Rules.meldValue(row(104,t(0,1),t(0,2))));
        assertEquals(39,Rules.meldValue(row(t(0,13),104,t(1,13))));
        assertEquals(60,Rules.rackValue(row(104,105)));
    }
    @Test public void openingMustUseOwnTilesAndReachThirty(){
        List<List<Integer>> before=board(row(t(0,1),t(0,2),t(0,3)));
        List<Integer> rack=row(t(0,4),t(1,10),t(2,10),t(3,10));
        List<List<Integer>> good=Rules.copyBoard(before);good.add(row(t(1,10),t(2,10),t(3,10)));
        Rules.validateMove(before,rack,false,good);
        invalid(()->Rules.validateMove(before,rack,false,board(row(t(0,1),t(0,2),t(0,3),t(0,4)),row(t(1,10),t(2,10),t(3,10)))));
        invalid(()->Rules.validateMove(new ArrayList<>(),row(0,1,2),false,board(row(0,1,2))));
    }
    @Test public void noTheftDuplicatesOrDisappearingTiles(){
        List<List<Integer>> before=board(row(0,1,2));List<Integer> rack=row(3,4,5);
        Rules.validateMove(before,rack,true,board(row(0,1,2,3,4,5)));
        invalid(()->Rules.validateMove(before,rack,true,board(row(1,2,3))));
        invalid(()->Rules.validateMove(before,rack,true,board(row(0,1,2,3,4,5,6))));
        invalid(()->Rules.validateMove(before,rack,true,board(row(0,1,2),row(0,1,2))));
        invalid(()->Rules.validateMove(before,rack,true,before));
    }
    @Test public void canSplitRunsAndReuseJoker(){
        List<List<Integer>> before=board(row(0,1,2,3,4,5));
        Rules.validateMove(before,row(16),true,board(row(0,1,2,3),row(16,4,5)));
        before=board(row(8,104,10));
        Rules.validateMove(before,row(9,26,27),true,board(row(8,9,10),row(26,27,104)));
    }
    @Test public void dealAndViewNeverLeakOpponents(){
        Room r=new Room();r.code="TEST";r.host="a";r.members=Arrays.asList(new Game.Player("a","A",false),new Game.Player("b","B",true));
        r.game=Game.start(r.members,new Random(1),60,100);
        assertEquals(78,r.game.pool.size());assertEquals(14,r.view("a").rack.size());
        Set<Integer> all=new HashSet<>(r.game.pool);for(Game.Player p:r.game.players)all.addAll(p.rack);assertEquals(106,all.size());
        String json=new com.google.gson.Gson().toJson(r.view("a"));assertFalse(json.contains("\"pool\":"));assertEquals(1,json.split("\"rack\":",-1).length-1);
        invalid(()->r.view("stranger"));
    }
    @Test public void transactionalMovesStaleRevisionAndTimeout(){
        Game g=Game.start(Arrays.asList(new Game.Player("a","A",false),new Game.Player("b","B",false)),new Random(2),60,1000);
        String current=g.current().id;List<Integer> before=new ArrayList<>(g.current().rack);
        invalid(()->g.play(current,g.revision,board(row(0,0,0)),1001));assertEquals(before,g.current().rack);
        g.editing=true;g.timeout(61000);assertEquals(17,g.player(current).rack.size());assertEquals(1,g.revision);
        invalid(()->g.draw(current,0,62000));
        g.timeout(61000);assertEquals(1,g.revision);
    }
    @Test public void winningScoresBalanceIncludingBlockedGame(){
        Game g=new Game();Game.Player a=new Game.Player("a","A",false),b=new Game.Player("b","B",false);
        a.rack=row(9,35,61);b.rack=row(104,0);g.players.add(a);g.players.add(b);
        g.play("a",0,board(row(9,35,61)),0);assertTrue(g.finished);assertEquals(31,a.score);assertEquals(-31,b.score);
        g=new Game();a=new Game.Player("a","A",false);b=new Game.Player("b","B",false);a.rack=row(0);b.rack=row(10);g.players.add(a);g.players.add(b);
        g.draw("a",0,0);g.draw("b",1,0);assertTrue(g.finished);assertEquals(10,a.score);assertEquals(-10,b.score);
    }
    @Test public void botCanCombineSeveralOpeningMelds(){
        List<Integer> rack=row(t(0,4),t(1,4),t(2,4),t(0,6),t(1,6),t(2,6));
        List<List<Integer>> move=Bot.choose(rack,new ArrayList<>(),false);assertNotNull(move);assertEquals(6,Rules.flatten(move).size());
    }
    @Test public void completeBotGamesConserveAllTiles(){
        for(int seed=0;seed<20;seed++){
            Game g=Game.start(Arrays.asList(new Game.Player("a","A",true),new Game.Player("b","B",true),new Game.Player("c","C",true)),new Random(seed),0,0);
            int turns=0;while(!g.finished&&turns++<800){
                Bot.turn(g,turns);List<Integer> all=new ArrayList<>(g.pool);for(Game.Player p:g.players)all.addAll(p.rack);for(List<Integer> meld:g.board){assertTrue(Rules.meldValue(meld)>=0);all.addAll(meld);}
                assertEquals(106,all.size());assertEquals(106,new HashSet<>(all).size());
            }
            assertTrue("seed "+seed,g.finished);assertEquals(0,g.players.stream().mapToInt(p->p.score).sum());
        }
    }
}
