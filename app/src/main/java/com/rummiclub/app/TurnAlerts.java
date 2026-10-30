package com.rummiclub.app;

/** Deduplicates alerts across redraws and repeated polls of the same turn. */
final class TurnAlerts {
    enum Event { NONE, TURN, WARNING }
    static long seconds(long deadline,long now){return deadline==0?0:Math.max(0,(deadline-now+999)/1000);}
    static int urgency(long deadline,long now){if(deadline==0)return 0;long left=seconds(deadline,now);return left<=10?2:left<=20?1:0;}
    private String key="";
    private boolean warned;
    Event update(String room,long revision,boolean own,boolean timed,long remaining){
        String next=room+":"+revision;
        boolean changed=!next.equals(key);
        if(changed){key=next;warned=false;}
        if(own&&timed&&remaining>0&&remaining<=10&&!warned){warned=true;return Event.WARNING;}
        return changed?Event.TURN:Event.NONE;
    }
}
