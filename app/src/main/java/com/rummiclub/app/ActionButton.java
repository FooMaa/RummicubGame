package com.rummiclub.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;

/** Consistent scalable line icons with accessible names and long-press explanations. */
final class ActionButton extends Button {
    private final String icon;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean primary;
    ActionButton(Context context,String icon,String description,boolean primary){
        super(context);this.icon=icon;this.primary=primary;setText("");setContentDescription(description);setTooltipText(description);
        setPadding(0,0,0,0);setMinWidth(0);setMinimumWidth(0);setMinHeight(0);setMinimumHeight(0);
        GradientDrawable bg=new GradientDrawable();bg.setColor(primary?0xFFC5F277:0xFF2B4038);bg.setCornerRadius(14*getResources().getDisplayMetrics().density);setBackground(bg);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.save();float size=Math.min(getWidth(),getHeight())*.52f;
        canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/24,size/24);
        p.setColor(primary?0xFF111C1B:0xFFF3F5ED);p.setStrokeWidth(1.9f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);
        switch(icon){
            case "check":path(canvas,4,12,9,17,20,6);break;
            case "plus":line(canvas,12,4,12,20);line(canvas,4,12,20,12);break;
            case "undo":path(canvas,8,4,3,9,8,14);path(canvas,3,9,14,9,19,12,19,18);break;
            case "return":path(canvas,4,15,4,20,20,20,20,15);path(canvas,8,10,12,14,16,10);line(canvas,12,3,12,14);break;
            case "draw":canvas.drawRoundRect(3,6,16,21,3,3,p);path(canvas,8,3,20,3,20,16);line(canvas,6,13,13,13);line(canvas,9.5f,9.5f,9.5f,16.5f);break;
            case "sort":path(canvas,4,4,4,20,1,17);line(canvas,9,6,22,6);line(canvas,9,12,18,12);line(canvas,9,18,14,18);break;
            case "clear":canvas.drawCircle(12,12,9,p);line(canvas,8,8,16,16);line(canvas,8,16,16,8);break;
            case "home":path(canvas,2,11,12,3,22,11);path(canvas,5,10,5,21,10,21,10,14,15,14,15,21,19,21,19,10);break;
            case "help":canvas.drawCircle(12,12,9,p);path(canvas,9,8,10,6,14,6,16,8,16,10,12,13,12,15);canvas.drawPoint(12,18,p);break;
            case "exit":path(canvas,9,3,3,3,3,21,9,21);line(canvas,8,12,22,12);path(canvas,17,7,22,12,17,17);break;
            case "play":path(canvas,7,3,21,12,7,21,7,3);break;
            case "bot":canvas.drawRoundRect(3,7,21,21,3,3,p);line(canvas,12,3,12,7);canvas.drawCircle(12,2,1,p);canvas.drawPoint(8,12,p);canvas.drawPoint(16,12,p);line(canvas,8,17,16,17);break;
            case "users":canvas.drawCircle(9,7,4,p);canvas.drawArc(2,13,16,26,180,180,false,p);canvas.drawArc(12,3,21,11,-90,180,false,p);canvas.drawArc(13,13,23,23,190,150,false,p);break;
            case "settings":canvas.drawCircle(12,12,7,p);canvas.drawCircle(12,12,2.5f,p);for(int i=0;i<8;i++){canvas.save();canvas.rotate(i*45,12,12);line(canvas,12,2,12,5);canvas.restore();}break;
            case "share":canvas.drawCircle(5,12,3,p);canvas.drawCircle(19,5,3,p);canvas.drawCircle(19,19,3,p);line(canvas,8,10,16,6);line(canvas,8,14,16,18);break;
            case "key":canvas.drawCircle(8,8,5,p);path(canvas,12,12,21,21,23,19,20,16,18,18);break;
            case "save":path(canvas,4,3,18,3,21,6,21,21,3,21,3,3,4,3);canvas.drawRect(7,3,16,9,p);canvas.drawRect(7,14,17,21,p);break;
            default:canvas.drawCircle(5,12,1,p);canvas.drawCircle(12,12,1,p);canvas.drawCircle(19,12,1,p);
        }
        canvas.restore();
    }
    private void line(Canvas c,float x,float y,float a,float b){c.drawLine(x,y,a,b,p);}
    private void path(Canvas c,float...points){Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,p);}
}
