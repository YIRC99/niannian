package dev.niannian.reminder;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** A real attachment with visible loading and retry states; artwork is never used as a photo. */
final class RecordPhotoView extends FrameLayout {
    private final ImageView image;
    private final LinearLayout placeholder;
    private final ProgressBar progress;
    private final GardenIconView failureIcon;
    private final TextView title, caption;

    public RecordPhotoView(Context context) { this(context,false); }

    RecordPhotoView(Context context, boolean fullSize) {
        super(context);
        GradientDrawable surface=new GradientDrawable();surface.setColor(0xfff5f4ef);
        surface.setCornerRadius(dp(14));surface.setStroke(dp(1),0xffe1e5da);setBackground(surface);setClipToOutline(true);
        image=new ImageView(context);image.setScaleType(fullSize?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(image,new LayoutParams(-1,-1));
        placeholder=new LinearLayout(context);placeholder.setOrientation(LinearLayout.VERTICAL);placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(dp(20),dp(20),dp(20),dp(20));addView(placeholder,new LayoutParams(-1,-1));
        progress=new ProgressBar(context);progress.setIndeterminateTintList(ColorStateList.valueOf(0xff7b9876));
        placeholder.addView(progress,new LinearLayout.LayoutParams(dp(28),dp(28)));
        failureIcon=new GardenIconView(context,"photo_error",0xff969f94);
        placeholder.addView(failureIcon,new LinearLayout.LayoutParams(dp(64),dp(64)));
        title=label(context,15,0xff283c32);caption=label(context,12,0xff68786d);
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(-1,-2);titleLp.topMargin=dp(18);placeholder.addView(title,titleLp);
        LinearLayout.LayoutParams captionLp=new LinearLayout.LayoutParams(-1,-2);captionLp.topMargin=dp(10);placeholder.addView(caption,captionLp);
        placeholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void loading(){
        image.setImageDrawable(null);placeholder.setVisibility(VISIBLE);progress.setVisibility(VISIBLE);failureIcon.setVisibility(GONE);
        title.setText("正在读取照片…");caption.setText("");setContentDescription("正在读取完成凭证照片");setOnClickListener(null);setClickable(false);setFocusable(false);
    }

    void failed(Runnable retry){
        progress.setVisibility(GONE);failureIcon.setVisibility(VISIBLE);placeholder.setVisibility(VISIBLE);
        title.setText("照片加载失败，点击重试");caption.setText("轻触图片重新加载");setContentDescription("照片加载失败，点击重试");
        setOnClickListener(v->retry.run());setFocusable(true);
    }

    void ready(Bitmap bitmap,Runnable open){
        progress.setVisibility(GONE);placeholder.setVisibility(GONE);image.setImageBitmap(bitmap);
        setContentDescription(open==null?"完成凭证照片":"完成凭证照片，点击查看大图");
        setOnClickListener(open==null?null:v->open.run());setClickable(open!=null);setFocusable(open!=null);
    }

    private TextView label(Context context,int size,int color){
        TextView label=new TextView(context);label.setTextSize(size);label.setTextColor(color);label.setGravity(Gravity.CENTER);label.setIncludeFontPadding(false);return label;
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
