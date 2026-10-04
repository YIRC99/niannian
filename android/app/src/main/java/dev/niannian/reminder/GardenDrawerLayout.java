package dev.niannian.reminder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Fixed garden with an overlay drawer. Only the drawer's contents scroll. */
@android.annotation.SuppressLint("ViewConstructor") // Composed in Java with its existing scene and controls; never inflated from XML.
final class GardenDrawerLayout extends FrameLayout {
    interface Listener { void onExpandedChanged(boolean expanded); }
    private final View scene;
    private final LinearLayout sheet, header;
    private final ScrollView contents;
    private final int slop;
    private boolean expanded, headerTouch, dragging;
    private float downX, downY, startTranslation, travel;
    private Listener listener;
    private Runnable opening;

    GardenDrawerLayout(Context context,View scene,LinearLayout sheet,LinearLayout header,ScrollView contents){
        super(context);this.scene=scene;this.sheet=sheet;this.header=header;this.contents=contents;
        slop=ViewConfiguration.get(context).getScaledTouchSlop();setClipChildren(true);sheet.setClickable(true);
        addView(scene);addView(sheet);
    }

    void setListener(Listener listener){this.listener=listener;}
    void setOnOpening(Runnable opening){this.opening=opening;}
    void setExpanded(boolean value,boolean animate){
        expanded=value;sheet.animate().cancel();
        if(value&&opening!=null)opening.run();
        if(listener!=null)listener.onExpandedChanged(value);
        contents.setImportantForAccessibility(value?IMPORTANT_FOR_ACCESSIBILITY_AUTO:IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        float target=value?0:travel;
        if(animate&&isLaidOut()&&ValueAnimator.areAnimatorsEnabled())
            sheet.animate().translationY(target).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        else sheet.setTranslationY(target);
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width,height);
        int exactWidth=MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY);
        header.measure(MeasureSpec.makeMeasureSpec(Math.max(0,width-sheet.getPaddingLeft()-sheet.getPaddingRight()),MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height,MeasureSpec.AT_MOST));
        int peek=Math.min(height,header.getMeasuredHeight()+sheet.getPaddingTop()+sheet.getPaddingBottom());
        int sheetHeight=Math.min(height,Math.max(peek,Math.round(height*.78f)));
        sheet.measure(exactWidth,MeasureSpec.makeMeasureSpec(sheetHeight,MeasureSpec.EXACTLY));
        // The scene always reserves the collapsed header, independent of drawer state and diary length.
        scene.measure(exactWidth,MeasureSpec.makeMeasureSpec(Math.max(0,height-peek+dp(18)),MeasureSpec.EXACTLY));
        travel=Math.max(0,sheetHeight-peek);
    }

    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        scene.layout(0,0,getMeasuredWidth(),scene.getMeasuredHeight());
        int top=getMeasuredHeight()-sheet.getMeasuredHeight();sheet.layout(0,top,getMeasuredWidth(),getMeasuredHeight());
        if(!dragging){sheet.animate().cancel();sheet.setTranslationY(expanded?0:travel);}
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            downX=event.getX();downY=event.getY();startTranslation=sheet.getTranslationY();dragging=false;
            float top=sheet.getTop()+startTranslation;
            headerTouch=downY>=top&&downY<top+sheet.getPaddingTop()+header.getHeight();
        }else if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&headerTouch){
            float dy=event.getY()-downY;
            if(Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(event.getX()-downX)){
                dragging=true;sheet.animate().cancel();if(dy<0&&opening!=null)opening.run();return true;
            }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(!dragging)return super.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
            sheet.setTranslationY(Math.max(0,Math.min(travel,startTranslation+event.getY()-downY)));return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
            float delta=event.getY()-downY;dragging=false;
            boolean next=event.getActionMasked()==MotionEvent.ACTION_CANCEL?expanded:
                    Math.abs(delta)>dp(32)?delta<0:sheet.getTranslationY()<travel/2;
            setExpanded(next,true);return true;
        }
        return true;
    }

    @Override public boolean performClick(){super.performClick();setExpanded(!expanded,true);return true;}
    @Override protected void onDetachedFromWindow(){sheet.animate().cancel();super.onDetachedFromWindow();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
