package com.rummiclub.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class TurnAlertsTest {
    @Test public void warnsOnceAndDoesNotRepeatOnPollOrRedraw(){
        TurnAlerts a=new TurnAlerts();assertEquals(TurnAlerts.Event.TURN,a.update("room",1,true,true,60));
        assertEquals(TurnAlerts.Event.NONE,a.update("room",1,true,true,20));
        assertEquals(TurnAlerts.Event.WARNING,a.update("room",1,true,true,10));
        assertEquals(TurnAlerts.Event.NONE,a.update("room",1,true,true,9));
        assertEquals(TurnAlerts.Event.NONE,a.update("room",1,true,true,10));
        assertEquals(TurnAlerts.Event.TURN,a.update("room",2,false,true,60));
        assertEquals(TurnAlerts.Event.NONE,a.update("room",2,false,true,10));
        assertEquals(TurnAlerts.Event.WARNING,a.update("room",3,true,true,8));
        assertEquals(TurnAlerts.Event.TURN,a.update("other",3,true,false,0));
    }
    @Test public void timerThresholdsAndUnlimitedGames(){
        assertEquals(0,TurnAlerts.urgency(60000,39000));assertEquals(1,TurnAlerts.urgency(60000,40000));
        assertEquals(1,TurnAlerts.urgency(60000,49000));assertEquals(2,TurnAlerts.urgency(60000,50000));
        assertEquals(2,TurnAlerts.urgency(60000,61000));assertEquals(0,TurnAlerts.seconds(60000,61000));
        assertEquals(0,TurnAlerts.urgency(0,100000));assertEquals(1,TurnAlerts.seconds(60000,59999));
    }
}
