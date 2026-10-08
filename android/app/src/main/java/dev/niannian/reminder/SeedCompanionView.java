package dev.niannian.reminder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/** Character animation uses drawn action frames, with no work outside its visible page. */
final class SeedCompanionView extends View {
    enum Pose { LISTEN, THINK, SPEAK, HAPPY }
    private final Bitmap sheet;
    private final Bitmap thinkingSheet;
    private static final int[] THINK_DURATIONS={360,90,90,110,140,140,180,400,90,90,140,260,140,120,140,220};
    private static final int THINK_PERIOD=2710;
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
    private int lastThinkingFrame=-1,exitThinkingFrame=-1;
    private long exitStarted;

    SeedCompanionView(Context context){
        super(context);
        sheet=GardenArtCache.backdrop(getResources(),R.drawable.sowing_companion_sheet);
        thinkingSheet=GardenArtCache.backdrop(getResources(),R.drawable.sowing_thinking_sheet);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        clock.setDuration(3200);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a->{
            if(!ValueAnimator.areAnimatorsEnabled()){stopMotion();invalidate();return;}
            phase=(float)a.getAnimatedValue();
            if(pose==Pose.THINK){int frame=thinkingFrame(clock.getCurrentPlayTime());if(frame==lastThinkingFrame)return;lastThinkingFrame=frame;}
            invalidate();
        });
    }
    void setPose(Pose next){
        if(pose==next)return;
        exitThinkingFrame=pose==Pose.THINK?Math.max(0,lastThinkingFrame):-1;
        exitStarted=android.os.SystemClock.uptimeMillis();
        clock.cancel();lastThinkingFrame=-1;
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
        target.set(cx-size/2,cy-size/2,cx+size/2,cy+size/2);
        if(pose==Pose.THINK&&thinkingSheet!=null){drawThinkingFrame(canvas,motion?thinkingFrame(clock.getCurrentPlayTime()):7,255);return;}
        float tilt=pose==Pose.HAPPY?wave*2.2f:wave*.8f;
        canvas.save();canvas.translate(0,motion?-wave*size*.009f:0);
        canvas.rotate(tilt,cx,cy+size*.25f);
        float breath=motion?1f+wave*.009f:1f;canvas.scale(1f,breath,cx,cy+size*.42f);
        target.set(cx-size/2,cy-size/2,cx+size/2,cy+size/2);
        int frame=pose==Pose.THINK?1:pose==Pose.HAPPY?3:pose==Pose.SPEAK?2:0;
        // Speech is brief; never make a user wait for text to type itself out.
        if(motion&&pose==Pose.SPEAK&&age<2200 && (age/350)%2==1)frame=0;
        drawFrame(canvas,frame);canvas.restore();
        if(motion&&exitThinkingFrame>=0){long elapsed=android.os.SystemClock.uptimeMillis()-exitStarted;if(elapsed<160)drawThinkingFrame(canvas,exitThinkingFrame,(int)(255*(1-elapsed/160f)));else exitThinkingFrame=-1;}
        if(pose==Pose.HAPPY&&motion&&age<1800){
            float t=age/1800f;paint.setColor(0xffd6af58);paint.setAlpha((int)(255*(1-t)));
            for(int i=0;i<5;i++){double angle=Math.PI*(1.12+i*.19);float x=cx+(float)Math.cos(angle)*size*(.34f+.18f*t),y=cy+(float)Math.sin(angle)*size*(.34f+.18f*t);float r=size*.032f*(1-t*.5f);sparkle.reset();sparkle.moveTo(x,y-r);sparkle.lineTo(x+r*.35f,y-r*.35f);sparkle.lineTo(x+r,y);sparkle.lineTo(x+r*.35f,y+r*.35f);sparkle.lineTo(x,y+r);sparkle.lineTo(x-r*.35f,y+r*.35f);sparkle.lineTo(x-r,y);sparkle.lineTo(x-r*.35f,y-r*.35f);sparkle.close();canvas.drawPath(sparkle,paint);}paint.setAlpha(255);
        }
    }
    private static int thinkingFrame(long elapsed){long time=Math.max(0,elapsed)%THINK_PERIOD;for(int i=0;i<THINK_DURATIONS.length;i++){if(time<THINK_DURATIONS[i])return i;time-=THINK_DURATIONS[i];}return 0;}
    private void drawThinkingFrame(Canvas canvas,int index,int alpha){if(thinkingSheet==null||thinkingSheet.isRecycled())return;int width=thinkingSheet.getWidth()/4,height=thinkingSheet.getHeight()/4;source.set(index%4*width,index/4*height,(index%4+1)*width,(index/4+1)*height);paint.setAlpha(alpha);canvas.drawBitmap(thinkingSheet,source,target,paint);paint.setAlpha(255);}
    private void drawFrame(Canvas canvas,int index){int width=sheet.getWidth()/2,height=sheet.getHeight()/2;source.set((index%2)*width,(index/2)*height,(index%2+1)*width,(index/2+1)*height);paint.setAlpha(255);canvas.drawBitmap(sheet,source,target,paint);}
}
