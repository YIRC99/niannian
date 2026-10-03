package dev.niannian.reminder;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;

import java.util.Arrays;

/** A decorative garden whose visible plants come only from the supplied task stages. */
public final class GardenSceneView extends View {
    private static final int MAX_PLANTS = 6;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path ground = new Path();
    private final RectF bounds = new RectF();
    private final Rect visible = new Rect();
    private final Bitmap[] plants = new Bitmap[4];
    private final Bitmap watering;
    private final float density;
    private final ValueAnimator breeze;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::updateMotion;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateMotion;
    private int[] stages = new int[0];
    private boolean hostActive = true;
    private float breezePhase;

    public GardenSceneView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
        setWillNotDraw(false);
        watering = GardenArtCache.load(getResources(), R.drawable.garden_watering);
        breeze = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        breeze.setDuration(7600L);
        breeze.setRepeatCount(ValueAnimator.INFINITE);
        breeze.setInterpolator(new LinearInterpolator());
        breeze.addUpdateListener(animation -> {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                stopBreeze();
                return;
            }
            breezePhase = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    /** Stages: 0 = planted seed, 1 = sprout, 2 = leaves, 3 = bloom; at most six are shown. */
    public void setStages(int[] taskStages) {
        int length = taskStages == null ? 0 : Math.min(taskStages.length, MAX_PLANTS);
        int[] next = new int[length];
        for (int i = 0; i < length; i++) next[i] = Math.max(0, Math.min(3, taskStages[i]));
        if (Arrays.equals(stages, next)) return;
        stages = next;
        for (int stage : stages) {
            if (plants[stage] == null) {
                plants[stage] = GardenArtCache.plant(getResources(), stage);
            }
        }
        updateMotion();
        invalidate();
    }

    public void setHostActive(boolean active) {
        hostActive = active;
        updateMotion();
    }

    private void updateMotion() {
        if (breeze == null) return;
        boolean hasLeaves = false;
        for (int stage : stages) if (stage > 0) { hasLeaves = true; break; }
        boolean allowed = hostActive && hasLeaves && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && hasWindowFocus()
                && ValueAnimator.areAnimatorsEnabled() && getLocalVisibleRect(visible)
                && visible.width() > 0 && visible.height() > 0;
        if (allowed && !breeze.isStarted()) breeze.start();
        else if (!allowed) stopBreeze();
    }

    private void stopBreeze() {
        if (breeze == null) return;
        if (breeze.isStarted()) breeze.cancel();
        if (breezePhase != 0f) {
            breezePhase = 0f;
            invalidate();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        updateMotion();
    }

    @Override protected void onDetachedFromWindow() {
        if (getViewTreeObserver().isAlive()) {
            getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
            getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        }
        stopBreeze();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (breeze != null) updateMotion();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (breeze != null) updateMotion();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        updateMotion();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(dp(320), widthMeasureSpec),
                resolveSize(dp(200), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xfff6f8ee);
        canvas.drawRect(0, 0, w, h, paint);

        // Flat, rounded shapes keep the landscape as light as the illustrated plants.
        paint.setColor(0xfff4e6b7);
        canvas.drawCircle(w * .86f, h * .18f, Math.min(dp(15), h * .085f), paint);
        ground.reset();
        ground.moveTo(0, h * .52f);
        ground.cubicTo(w * .28f, h * .39f, w * .62f, h * .70f, w, h * .48f);
        ground.lineTo(w, h);
        ground.lineTo(0, h);
        ground.close();
        paint.setColor(0xffe8efe0);
        canvas.drawPath(ground, paint);

        ground.reset();
        ground.moveTo(0, h * .84f);
        ground.cubicTo(w * .32f, h * .70f, w * .67f, h * .76f, w, h * .88f);
        ground.lineTo(w, h);
        ground.lineTo(0, h);
        ground.close();
        paint.setColor(0xffdfead5);
        canvas.drawPath(ground, paint);

        if (stages.length == 0) {
            drawArt(canvas, watering, w * .50f, h * .87f,
                    Math.min(w * .46f, dp(145)), h * .69f);
            return;
        }

        if (stages.length <= 3) {
            drawRow(canvas, 0, stages.length, h * .85f,
                    Math.min(h * .66f, dp(124)), w);
        } else {
            int backCount = (stages.length + 1) / 2;
            drawRow(canvas, 0, backCount, h * .55f, h * .42f, w);
            drawRow(canvas, backCount, stages.length - backCount, h * .91f, h * .45f, w);
        }
    }

    private void drawRow(Canvas canvas, int start, int count, float baseline, float artHeight, float width) {
        float side = Math.min(dp(32), width * .10f);
        float slot = (width - side * 2f) / count;
        float artWidth = Math.min(slot * .83f, artHeight * 1.10f);
        for (int i = 0; i < count; i++) {
            float x = side + slot * (i + .5f);
            int stage = stages[start + i];
            float sway = stage == 0 || !breeze.isStarted() ? 0f
                    : (float) Math.sin(breezePhase + (start + i) * 1.71f) * (stage == 3 ? .85f : 1.2f);
            drawPlant(canvas, plants[stage], x, baseline, artWidth * (stage == 0 ? .72f : 1f),
                    artHeight * (stage == 0 ? .62f : stage == 1 ? .82f : stage == 2 ? .94f : 1f), sway,
                    stage == 1 ? .23f : .17f);
        }
    }

    private void drawPlant(Canvas canvas, Bitmap bitmap, float centerX, float baseline,
                           float maxWidth, float maxHeight, float sway, float soilFraction) {
        if (bitmap == null) return;
        if (sway == 0f) {
            drawArt(canvas, bitmap, centerX, baseline, maxWidth, maxHeight);
            return;
        }
        float scale = Math.min(maxWidth / bitmap.getWidth(), maxHeight / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        bounds.set(centerX - width / 2f, baseline - height, centerX + width / 2f, baseline);
        float root = baseline - height * soilFraction;
        paint.setColor(0xffffffff);
        // The illustrated soil never moves; only the plant above its root sways in the breeze.
        canvas.save();
        canvas.clipRect(bounds.left - dp(3), root, bounds.right + dp(3), baseline + dp(1));
        canvas.drawBitmap(bitmap, null, bounds, paint);
        canvas.restore();
        canvas.save();
        canvas.clipRect(bounds.left - dp(4), bounds.top - dp(4), bounds.right + dp(4), root + dp(.7f));
        canvas.rotate(sway, centerX, root);
        canvas.drawBitmap(bitmap, null, bounds, paint);
        canvas.restore();
    }

    private void drawArt(Canvas canvas, Bitmap bitmap, float centerX, float baseline,
                         float maxWidth, float maxHeight) {
        if (bitmap == null) return;
        float scale = Math.min(maxWidth / bitmap.getWidth(), maxHeight / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        bounds.set(centerX - width / 2f, baseline - height, centerX + width / 2f, baseline);
        paint.setColor(0xffffffff);
        canvas.drawBitmap(bitmap, null, bounds, paint);
    }

    private int dp(float value) { return Math.round(value * density); }
}
