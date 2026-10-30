package com.rummiclub.app;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.rummiclub.core.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Device smoke test: actual Android drag/drop, pinned rack, timer thresholds and rollback. */
public final class UiSmoke extends Instrumentation {
    private MainActivity activity;
    private final StringBuilder passed=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(400);main(()->fixture());waitForIdleSync();
            View clock=(View)get("clockLabel"),table=(View)get("scroll");Rect timerRect=rect(clock),tableRect=rect(table);
            check(tableRect.bottom<=timerRect.top,"Rack and timer are outside scrolling table");int y=timerRect.top;
            main(()->{((ScrollView)get("scroll")).setSmoothScrollingEnabled(false);((ScrollView)get("scroll")).fullScroll(View.FOCUS_DOWN);});waitForIdleSync();
            check(rect((View)get("clockLabel")).top==y,"Timer stays pinned while table scrolls");
            main(()->((ScrollView)get("scroll")).scrollTo(0,0));waitForIdleSync();
            check(findDescription("Добавить в начало ряда 1")!=null&&findDescription("Добавить в конец ряда 1")!=null,"Both row ends have insertion buttons");
            screenshot("ui-portrait.png");
            drag(tile(55),tile(1),.20f);
            screenshot("ui-dragged.png");check(draft().get(0).equals(Arrays.asList(0,55,1,2)),"Drag inserts a rack tile in the middle of a row: "+draft().get(0));
            // Drop onto the dock's title to avoid another tile consuming the drop.
            dragTo(tile(55),rect((View)((View)get("clockLabel")).getParent()).left+30,rect((View)((View)get("clockLabel")).getParent()).centerY());
            check(!Rules.flatten(draft()).contains(55),"Drag returns only own tile to the rack");
            main(()->tile(3).performClick());waitForIdleSync();main(()->findDescription("Добавить в конец ряда 1").performClick());waitForIdleSync();
            check(draft().get(0).equals(Arrays.asList(0,1,2,3)),"Tap plus remains available alongside drag/drop");
            main(()->findDescription("Восстановить стол на начало хода").performClick());waitForIdleSync();
            check(draft().equals(((Room.View)get("view")).board),"Reset restores the beginning-of-turn table");
            main(()->{Room.View v=(Room.View)get("view");v.deadline=System.currentTimeMillis()+19500;invoke("updateClock");});
            check(((TextView)get("clockLabel")).getCurrentTextColor()==0xFFFFD166,"Timer yellow at 20 seconds");
            main(()->{Room.View v=(Room.View)get("view");v.deadline=System.currentTimeMillis()+9500;invoke("updateClock");});
            check(((TextView)get("clockLabel")).getCurrentTextColor()==0xFFFF6B6B,"Timer red at 10 seconds");
            screenshot("ui-warning.png");
            main(()->{fixture();activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);});Thread.sleep(1400);waitForIdleSync();
            check(rect((View)get("clockLabel")).height()>0,"Rack and timer visible in landscape");
            check(((View)get("scroll")).getHeight()>80*activity.getResources().getDisplayMetrics().density,"Landscape keeps useful table space");
            check(rect(tile(1)).height()>=tile(1).getHeight()*.9,"Landscape shows a full table row without scrolling");screenshot("ui-landscape.png");
            main(()->{activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});Thread.sleep(1000);waitForIdleSync();
            main(()->fixture());main(()->invoke("controlsHelp"));waitForIdleSync();screenshot("ui-help.png");
            result.putString("stream","PASS\n"+passed);finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){StringWriter stack=new StringWriter();failure.printStackTrace(new PrintWriter(stack));result.putString("stream","FAIL\n"+passed+stack);finish(Activity.RESULT_CANCELED,result);}
    }
    private void fixture(){
        try{
            Room r=new Room();r.code="UITEST";r.host="local";r.turnSeconds=60;
            Game g=new Game();Game.Player a=new Game.Player("local","Вы",false),b=new Game.Player("other","Атлас",false);a.opened=true;
            a.rack=new ArrayList<>(Arrays.asList(3,4,5,6,7,8,9,10,11,12,104,105,55,56));b.rack=new ArrayList<>(Arrays.asList(70,71,72));g.players.add(a);g.players.add(b);
            for(int c=0;c<8;c++)g.board.add(new ArrayList<>(Arrays.asList(c*13,c*13+1,c*13+2)));
            Set<Integer> used=Rules.flatten(g.board);used.addAll(a.rack);used.addAll(b.rack);for(int i=0;i<106;i++)if(!used.contains(i))g.pool.add(i);
            g.turnSeconds=60;g.deadline=System.currentTimeMillis()+60000;r.game=g;r.members=g.players;
            set("local",r);set("online",false);set("view",r.view("local"));set("draft",Rules.copyBoard(g.board));set("screen","game");set("dirty",false);set("draftSerial",0L);set("syncedDraftSerial",0L);set("gameLayout",false);
            ((Set<?>)get("selected")).clear();invoke("gameScreen");
        }catch(Exception e){throw new RuntimeException(e);}
    }
    private void main(Runnable action){runOnMainSync(action);waitForIdleSync();SystemClock.sleep(160);}
    private Object get(String field){try{Field f=MainActivity.class.getDeclaredField(field);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private void set(String field,Object value){try{Field f=MainActivity.class.getDeclaredField(field);f.setAccessible(true);f.set(activity,value);}catch(Exception e){throw new RuntimeException(e);}}
    private void invoke(String name){try{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}}
    @SuppressWarnings("unchecked") private List<List<Integer>> draft(){return (List<List<Integer>>)get("draft");}
    private View findDescription(String text){return find(activity.getWindow().getDecorView(),v->text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()));}
    private TileView tile(int id){return (TileView)find(activity.getWindow().getDecorView(),v->{if(!(v instanceof TileView))return false;try{Field f=TileView.class.getDeclaredField("tile");f.setAccessible(true);return f.getInt(v)==id;}catch(Exception e){throw new RuntimeException(e);}});}
    interface Predicate{boolean matches(View v);}
    private View find(View root,Predicate p){if(p.matches(root))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),p);if(found!=null)return found;}return null;}
    private Rect rect(View v){if(v==null)throw new AssertionError("View missing");Rect r=new Rect();if(!v.getGlobalVisibleRect(r))throw new AssertionError("View not visible: "+v.getContentDescription());return r;}
    private void drag(View source,View target,float fraction)throws Exception{Rect t=rect(target);dragTo(source,t.left+t.width()*fraction,t.centerY());}
    private void dragTo(View source,float x,float y)throws Exception{
        Rect from=rect(source);long down=SystemClock.uptimeMillis();touch(down,MotionEvent.ACTION_DOWN,from.centerX(),from.centerY());Thread.sleep(ViewConfiguration.getLongPressTimeout()+180);
        for(int i=1;i<=24;i++){float f=i/24f;touch(down,MotionEvent.ACTION_MOVE,from.centerX()+(x-from.centerX())*f,from.centerY()+(y-from.centerY())*f);Thread.sleep(18);}
        touch(down,MotionEvent.ACTION_UP,x,y);Thread.sleep(250);waitForIdleSync();
    }
    private void touch(long down,int action,float x,float y){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);getUiAutomation().injectInputEvent(event,true);event.recycle();}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);passed.append(message).append('\n');}
    private void screenshot(String name)throws Exception{SystemClock.sleep(200);Bitmap image=getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
}
