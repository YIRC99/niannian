package dev.niannian.reminder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/** The approved four-expression IP, animated locally without timers outside its visible page. */
final class SeedCompanionView extends View {
    enum Pose { LISTEN, THINK, SPEAK, HAPPY }
    private final Bitmap sheet;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Rect source=new Rect(),visible=new Rect();
    private final RectF target=new RectF();
    private final Path sparkle=new Path();
    private final ValueAnimator clock=ValueAnimator.ofFloat(0f,1f);
    private final ViewTreeObserver.OnScrollChangedListener scrollListener=this::updateMotion;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener=this::updateMotion;
    private Pose pose=Pose.LISTEN;
    private boolean hostActive=true;
    private float phase;
    private long poseStarted;

    SeedCompanionView(Context context){
        super(context);
        sheet=GardenArtCache.backdrop(getResources(),R.drawable.sowing_companion_sheet);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        clock.setDuration(3200);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a->{
            if(!ValueAnimator.areAnimatorsEnabled()){stopMotion();invalidate();return;}
            phase=(float)a.getAnimatedValue();invalidate();
        });
    }
    void setPose(Pose next){
        if(pose==next)return;
        pose=next;poseStarted=android.os.SystemClock.uptimeMillis();phase=0f;
        invalidate();updateMotion();
    }
    void setHostActive(boolean active){hostActive=active;updateMotion();if(!active)finishEntrance();}
    Rect screenBounds(){int[] location=new int[2];getLocationOnScreen(location);return new Rect(location[0],location[1],location[0]+getWidth(),location[1]+getHeight());}
    void enterFrom(Rect previous){
        if(previous==null||previous.isEmpty())return;
        post(()->{
            if(!isAttachedToWindow()||!hostActive||!ValueAnimator.areAnimatorsEnabled())return;
            // The response now lives in a fixed header. Stay within its safe bounds.
            setScaleX(.94f);setScaleY(.94f);setAlpha(.6f);
            animate().alpha(1).scaleX(1).scaleY(1).setDuration(280)
                .setInterpolator(new PathInterpolator(.16f,1f,.3f,1f)).start();
        });
    }
    private void finishEntrance(){animate().cancel();setTranslationX(0);setTranslationY(0);setScaleX(1);setScaleY(1);setAlpha(1);}
    private void stopMotion(){clock.cancel();phase=0f;}
    private void updateMotion(){
        boolean running=hostActive&&isAttachedToWindow()&&getWindowVisibility()==VISIBLE&&isShown()
            &&hasWindowFocus()&&getGlobalVisibleRect(visible)&&ValueAnimator.areAnimatorsEnabled();
        if(running&&!clock.isStarted())clock.start();
        else if(!running){stopMotion();invalidate();}
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();getViewTreeObserver().addOnScrollChangedListener(scrollListener);getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);updateMotion();}
    @Override protected void onDetachedFromWindow(){stopMotion();finishEntrance();if(getViewTreeObserver().isAlive()){getViewTreeObserver().removeOnScrollChangedListener(scrollListener);getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);}super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(clock!=null)updateMotion();}
    @Override protected void onVisibilityChanged(View view,int visibility){super.onVisibilityChanged(view,visibility);if(clock!=null)updateMotion();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);updateMotion();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(sheet==null||sheet.isRecycled())return;
        boolean motion=clock.isRunning()&&ValueAnimator.areAnimatorsEnabled();
        float wave=motion?(float)Math.sin(phase*Math.PI*2):0f;
        long age=android.os.SystemClock.uptimeMillis()-poseStarted;
        float size=Math.min(getWidth(),getHeight())*.92f;
        float cx=getWidth()/2f,cy=getHeight()/2f;
        float tilt=pose==Pose.THINK?wave*3.2f:pose==Pose.HAPPY?wave*2.2f:wave*.8f;
        canvas.save();canvas.translate(0,motion?-wave*size*.009f:0);
        canvas.rotate(tilt,cx,cy+size*.25f);
        float breath=motion?1f+wave*.009f:1f;canvas.scale(1f,breath,cx,cy+size*.42f);
        target.set(cx-size/2,cy-size/2,cx+size/2,cy+size/2);
        int frame=pose==Pose.THINK?1:pose==Pose.HAPPY?3:pose==Pose.SPEAK?2:0;
        // Speech is brief; never make a user wait for text to type itself out.
        if(motion&&pose==Pose.SPEAK&&age<2200 && (age/350)%2==1)frame=0;
        drawFrame(canvas,frame);canvas.restore();
        if(pose==Pose.THINK){
            paint.setColor(0xff739165);
            for(int i=0;i<3;i++){paint.setAlpha(motion?90+(int)(100*(.5+.5*Math.sin(phase*Math.PI*2-i))):160);canvas.drawCircle(cx+size*.28f+i*size*.055f,cy-size*.26f,size*.017f,paint);}paint.setAlpha(255);
        }
        if(pose==Pose.HAPPY&&motion&&age<1800){
            float t=age/1800f;paint.setColor(0xffd6af58);paint.setAlpha((int)(255*(1-t)));
            for(int i=0;i<5;i++){double angle=Math.PI*(1.12+i*.19);float x=cx+(float)Math.cos(angle)*size*(.34f+.18f*t),y=cy+(float)Math.sin(angle)*size*(.34f+.18f*t);float r=size*.032f*(1-t*.5f);sparkle.reset();sparkle.moveTo(x,y-r);sparkle.lineTo(x+r*.35f,y-r*.35f);sparkle.lineTo(x+r,y);sparkle.lineTo(x+r*.35f,y+r*.35f);sparkle.lineTo(x,y+r);sparkle.lineTo(x-r*.35f,y+r*.35f);sparkle.lineTo(x-r,y);sparkle.lineTo(x-r*.35f,y-r*.35f);sparkle.close();canvas.drawPath(sparkle,paint);}paint.setAlpha(255);
        }
    }
    private void drawFrame(Canvas canvas,int index){int width=sheet.getWidth()/2,height=sheet.getHeight()/2;source.set((index%2)*width,(index/2)*height,(index%2+1)*width,(index/2+1)*height);paint.setAlpha(255);canvas.drawBitmap(sheet,source,target,paint);}
}
