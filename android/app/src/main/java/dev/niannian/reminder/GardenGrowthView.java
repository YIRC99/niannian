package dev.niannian.reminder;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;

/** A finite, root-anchored growth ceremony driven only by confirmed completion counts. */
public final class GardenGrowthView extends View {
    public interface OnPhaseChangedListener { void onPhaseChanged(String caption); }

    private static final long DURATION_MS = 2500L;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path landscape = new Path();
    private final Path circle = new Path();
    private final Path droplet = new Path();
    private final Path sparkle = new Path();
    private final RectF art = new RectF();
    private final RectF ellipse = new RectF();
    private final Rect visible = new Rect();
    private final Bitmap[] plants = new Bitmap[4];
    private final Bitmap watering;
    private final ValueAnimator ceremony;
    private final float density;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::updateVisibility;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateVisibility;
    private OnPhaseChangedListener phaseListener;
    private String caption = "把一点点坚持，种进生活里";
    private int fromStage;
    private int toStage;
    private boolean knownCount = true;
    private boolean growthAdded;
    private boolean hostActive = true;
    private boolean pendingStart;
    private boolean interrupted;
    private float progress = 1f;

    public GardenGrowthView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
        // The host exposes the stage and phase as ordinary accessible text.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < plants.length; i++) plants[i] = GardenArtCache.plant(getResources(), i);
        watering = GardenArtCache.load(getResources(), R.drawable.garden_watering);
        ceremony = ValueAnimator.ofFloat(0f, 1f);
        ceremony.setDuration(DURATION_MS);
        ceremony.setInterpolator(new LinearInterpolator());
        ceremony.addUpdateListener(animation -> {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                finishImmediately();
                return;
            }
            progress = (float) animation.getAnimatedValue();
            updateCaption();
            invalidate();
        });
        ceremony.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!interrupted) {
                    progress = 1f;
                    updateCaption();
                    invalidate();
                }
            }
        });
    }

    /** Never infers an extra completion: all displayed stages come from these counts. */
    public void showGrowth(int fromCount, int toCount, boolean added, boolean animate) {
        stopAnimator();
        knownCount = true;
        toStage = stageForCount(Math.max(0, toCount));
        growthAdded = added && Math.max(0, toCount) > Math.max(0, fromCount);
        fromStage = growthAdded ? stageForCount(Math.max(0, fromCount)) : toStage;
        progress = 1f;
        pendingStart = false;
        if (growthAdded && animate && ValueAnimator.areAnimatorsEnabled()) {
            pendingStart = true;
            progress = 0f;
            startIfVisible();
        }
        updateCaption();
        invalidate();
    }

    /** Older servers can confirm completion without supplying enough evidence of plant growth. */
    public void showRecorded() {
        stopAnimator();
        knownCount = false;
        growthAdded = false;
        pendingStart = false;
        progress = 1f;
        updateCaption();
        invalidate();
    }

    /** Replays the illustration only; it never changes a count or performs a network operation. */
    public void replay() {
        if (!knownCount || !growthAdded) return;
        stopAnimator();
        if (!ValueAnimator.areAnimatorsEnabled() || !hostActive) {
            finishImmediately();
            return;
        }
        progress = 0f;
        pendingStart = true;
        updateCaption();
        startIfVisible();
        invalidate();
    }

    public void finishImmediately() {
        pendingStart = false;
        stopAnimator();
        progress = 1f;
        updateCaption();
        invalidate();
    }

    public void setHostActive(boolean active) {
        hostActive = active;
        if (!active) finishImmediately();
        else startIfVisible();
    }

    public void setOnPhaseChangedListener(OnPhaseChangedListener listener) {
        phaseListener = listener;
        if (listener != null) listener.onPhaseChanged(caption);
    }

    private static int stageForCount(int count) {
        return count >= 7 ? 3 : count >= 3 ? 2 : count >= 1 ? 1 : 0;
    }

    private void updateCaption() {
        String next;
        if (!knownCount) next = "这次完成已经记下了";
        else if (!growthAdded) next = "花园正保持着现在的模样";
        else if (progress < .32f) next = "浇一点水，接住今天的努力";
        else if (progress < .50f) next = "养分正在慢慢抵达根部";
        else if (progress < .88f && toStage > fromStage) {
            next = toStage == 1 ? "新芽正一点点舒展开来" : toStage == 2 ? "叶子迎着光，慢慢长大" : "你种下的坚持，开花了";
        } else if (progress < .88f) next = "又积攒了一点生长的力量";
        else if (toStage > fromStage) next = toStage == 1 ? "第一株嫩芽，长出来了" : toStage == 2 ? "嫩芽已经长成一簇绿叶" : "一朵小花，为你的坚持盛开";
        else next = "今天的努力，已经被花园收藏";
        if (!next.equals(caption)) {
            caption = next;
            if (phaseListener != null) phaseListener.onPhaseChanged(next);
        }
    }

    private void stopAnimator() {
        if (ceremony == null || !ceremony.isStarted()) return;
        interrupted = true;
        ceremony.cancel();
        interrupted = false;
    }

    private boolean canAnimate() {
        return hostActive && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
                && hasWindowFocus() && ValueAnimator.areAnimatorsEnabled() && getLocalVisibleRect(visible)
                && visible.width() > 0 && visible.height() > 0;
    }

    private void startIfVisible() {
        if (ceremony == null || !pendingStart) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finishImmediately();
            return;
        }
        if (!canAnimate()) return;
        pendingStart = false;
        interrupted = false;
        ceremony.start();
    }

    private void updateVisibility() {
        if (ceremony == null) return;
        if (ceremony.isStarted() && !canAnimate()) finishImmediately();
        else startIfVisible();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        startIfVisible();
    }

    @Override protected void onDetachedFromWindow() {
        if (getViewTreeObserver().isAlive()) {
            getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
            getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        }
        finishImmediately();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (ceremony != null) updateVisibility();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (ceremony != null) updateVisibility();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        updateVisibility();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(dp(320), widthMeasureSpec), resolveSize(dp(280), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w * .5f, baseline = h * .85f;
        float radius = Math.min(w * .43f, h * .445f);
        float cy = baseline - radius * .83f;
        drawLandscape(canvas, cx, cy, radius, baseline);
        if (!knownCount) {
            drawBitmap(canvas, watering, cx, baseline - h * .04f, radius * 1.03f, radius * 1.12f, 1f);
            drawSparkle(canvas, cx - radius * .66f, cy - radius * .26f, dp(5), 0xffb4c894, .8f);
            drawSparkle(canvas, cx + radius * .69f, cy - radius * .55f, dp(4), 0xffdfc777, .9f);
            return;
        }
        float pulse = envelope(progress, .31f, .84f);
        drawNourishment(canvas, cx, baseline, radius, pulse);
        drawGrowth(canvas, cx, baseline, radius);
        if (growthAdded && progress < 1f) {
            drawWatering(canvas, cx, baseline, radius);
            drawParticles(canvas, cx, baseline, radius);
        }
    }

    private void drawLandscape(Canvas canvas, float cx, float cy, float radius, float baseline) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffedf3e6);
        canvas.drawCircle(cx, cy, radius, paint);
        circle.reset();
        circle.addCircle(cx, cy, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(circle);
        paint.setColor(0xfff6e7b0);
        canvas.drawCircle(cx + radius * .59f, cy - radius * .54f, radius * .17f, paint);
        paint.setColor(0xfff7f8ee);
        ellipse.set(cx - radius * .91f, cy - radius * .42f, cx - radius * .29f, cy - radius * .27f);
        canvas.drawOval(ellipse, paint);
        landscape.reset();
        landscape.moveTo(cx - radius, cy + radius * .26f);
        landscape.cubicTo(cx - radius * .32f, cy - radius * .02f, cx + radius * .25f, cy + radius * .61f, cx + radius, cy + radius * .29f);
        landscape.lineTo(cx + radius, cy + radius);
        landscape.lineTo(cx - radius, cy + radius);
        landscape.close();
        paint.setColor(0xffdee9d4);
        canvas.drawPath(landscape, paint);
        landscape.reset();
        landscape.moveTo(cx - radius, baseline - radius * .12f);
        landscape.cubicTo(cx - radius * .35f, baseline - radius * .30f, cx + radius * .35f, baseline - radius * .02f, cx + radius, baseline - radius * .24f);
        landscape.lineTo(cx + radius, cy + radius);
        landscape.lineTo(cx - radius, cy + radius);
        landscape.close();
        paint.setColor(0xffd1e0c5);
        canvas.drawPath(landscape, paint);
        canvas.restore();
        paint.setColor(0x16738859);
        ellipse.set(cx - radius * .54f, baseline - radius * .08f, cx + radius * .54f, baseline + radius * .075f);
        canvas.drawOval(ellipse, paint);
        // A few grounded grass strokes make the illustration feel planted.
        paint.setColor(0xffa9bd98);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - radius * .70f, baseline - radius * .04f, cx - radius * .73f, baseline - radius * .12f, paint);
        canvas.drawLine(cx - radius * .70f, baseline - radius * .04f, cx - radius * .65f, baseline - radius * .09f, paint);
        canvas.drawLine(cx + radius * .66f, baseline, cx + radius * .69f, baseline - radius * .09f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawGrowth(Canvas canvas, float cx, float baseline, float radius) {
        if (!growthAdded || progress >= 1f) {
            drawPlant(canvas, toStage, cx, baseline, radius, 1f, 1f, 0f, 1f);
            return;
        }
        float growth = smooth(segment(progress, .42f, .87f));
        if (toStage == fromStage) {
            // The soil stays still; only the living upper part answers the nutrient pulse.
            float sway = (float) Math.sin(growth * Math.PI * 2) * 2.2f * (1f - growth);
            drawPlant(canvas, toStage, cx, baseline, radius, 1f, 1f, sway, 1f + .025f * (float) Math.sin(growth * Math.PI));
            return;
        }
        float sequence = growth * (toStage - fromStage);
        int stage = Math.min(toStage - 1, fromStage + (int) sequence);
        float local = Math.min(1f, sequence - (stage - fromStage));
        if (growth <= 0f) {
            drawPlant(canvas, fromStage, cx, baseline, radius, 1f, 1f, 0f, 1f);
            return;
        }
        float reveal = smooth(local);
        float oldAlpha = 1f - smooth(segment(local, .12f, .69f));
        float newAlpha = smooth(segment(local, .06f, .43f));
        drawPlant(canvas, stage, cx, baseline, radius, oldAlpha, 1f, -1.8f * reveal, 1f);
        float sway = (float) Math.sin(local * Math.PI * 2.25f) * 3.3f * (1f - local);
        drawPlant(canvas, stage + 1, cx, baseline, radius, newAlpha, reveal, sway, .85f + .15f * reveal);
    }

    private void drawPlant(Canvas canvas, int stage, float cx, float baseline, float radius,
                           float alpha, float reveal, float sway, float scaleY) {
        Bitmap bitmap = plants[stage];
        if (bitmap == null || alpha <= 0f) return;
        float maxH = radius * (stage == 0 ? .68f : stage == 1 ? 1.09f : stage == 2 ? 1.33f : 1.57f);
        float maxW = radius * (stage == 0 ? 1.02f : stage == 1 ? 1.18f : 1.25f);
        fit(bitmap, cx, baseline, maxW, maxH);
        float height = art.height();
        // Keep the mound pinned while the stem/leaves unfurl above it.
        float root = baseline - height * (stage == 0 ? .23f : stage == 1 ? .23f : .17f);
        paint.setColor(0xffffffff);
        paint.setAlpha(Math.round(255f * clamp(alpha)));
        canvas.save();
        canvas.clipRect(art.left - dp(5), root, art.right + dp(5), baseline + dp(2));
        canvas.drawBitmap(bitmap, null, art, paint);
        canvas.restore();
        canvas.save();
        canvas.clipRect(cx - radius, baseline - height * (.24f + .76f * reveal), cx + radius, root + dp(.8f));
        canvas.rotate(sway, cx, root);
        canvas.scale(1f, scaleY, cx, root);
        canvas.drawBitmap(bitmap, null, art, paint);
        canvas.restore();
        paint.setAlpha(255);
    }

    private void drawNourishment(Canvas canvas, float cx, float baseline, float radius, float strength) {
        if (!growthAdded || strength <= 0f || progress >= 1f) return;
        paint.setColor(0xfff8edb9);
        paint.setAlpha(Math.round(85f * strength));
        canvas.drawCircle(cx, baseline - radius * .34f, radius * (.42f + strength * .08f), paint);
        paint.setAlpha(255);
        for (int i = 0; i < 2; i++) {
            float phase = segment(progress, .31f + i * .13f, .68f + i * .13f);
            if (phase <= 0f || phase >= 1f) continue;
            float rx = radius * (.17f + phase * .59f);
            float ry = radius * (.037f + phase * .09f);
            ellipse.set(cx - rx, baseline - ry, cx + rx, baseline + ry);
            paint.setColor(0xff81a77b);
            paint.setAlpha(Math.round(105f * (1f - phase)));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.4f));
            canvas.drawOval(ellipse, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }

    private void drawWatering(Canvas canvas, float cx, float baseline, float radius) {
        float alpha = smooth(segment(progress, .025f, .14f)) * (1f - smooth(segment(progress, .47f, .61f)));
        if (watering == null || alpha <= 0f) return;
        float pour = smooth(segment(progress, .08f, .23f)) * (1f - smooth(segment(progress, .42f, .58f)));
        float x = cx + radius * .69f;
        float bottom = baseline - radius * 1.15f - radius * .10f * (1f - alpha);
        fit(watering, x, bottom, radius * .67f, radius * .64f);
        canvas.save();
        canvas.rotate(-21f * pour, art.centerX(), art.centerY());
        paint.setColor(0xffffffff);
        paint.setAlpha(Math.round(255f * alpha));
        canvas.drawBitmap(watering, null, art, paint);
        canvas.restore();
        paint.setAlpha(255);
        // Each droplet follows its own short curve from the tilted spout into the soil.
        for (int i = 0; i < 6; i++) {
            float fall = segment(progress, .17f + i * .040f, .38f + i * .040f);
            if (fall <= 0f || fall >= 1f) continue;
            float sx = cx + radius * (.39f + (i % 2) * .024f);
            float sy = baseline - radius * 1.39f;
            float ex = cx + radius * ((i % 3 - 1) * .065f);
            float ey = baseline - radius * .11f;
            float oneMinus = 1f - fall;
            float dx = oneMinus * oneMinus * sx + 2f * oneMinus * fall * (cx + radius * .08f) + fall * fall * ex;
            float dy = oneMinus * oneMinus * sy + 2f * oneMinus * fall * (sy + radius * .34f) + fall * fall * ey;
            drawDrop(canvas, dx, dy, radius * (.025f + (i % 2) * .005f), .83f * (1f - smooth(segment(fall, .84f, 1f))));
        }
    }

    private void drawDrop(Canvas canvas, float x, float y, float size, float alpha) {
        droplet.reset();
        droplet.moveTo(x, y - size * 1.65f);
        droplet.cubicTo(x + size * 1.15f, y - size * .10f, x + size, y + size, x, y + size);
        droplet.cubicTo(x - size, y + size, x - size * 1.15f, y - size * .10f, x, y - size * 1.65f);
        paint.setColor(0xff87bfd1);
        paint.setAlpha(Math.round(255 * alpha));
        canvas.drawPath(droplet, paint);
        paint.setColor(0xffe1f3f6);
        paint.setAlpha(Math.round(230 * alpha));
        canvas.drawCircle(x - size * .24f, y - size * .1f, size * .22f, paint);
        paint.setAlpha(255);
    }

    private void drawParticles(Canvas canvas, float cx, float baseline, float radius) {
        // Eight deterministic motes; no random work or particle allocation on the UI thread.
        for (int i = 0; i < 8; i++) {
            float flight = segment(progress, .38f + (i % 4) * .055f, .75f + (i % 4) * .055f);
            if (flight <= 0f || flight >= 1f) continue;
            float direction = i % 2 == 0 ? -1f : 1f;
            float x = cx + direction * radius * (.14f + (i % 3) * .11f + (float) Math.sin(flight * Math.PI) * .17f);
            float y = baseline - radius * (.12f + flight * (.57f + (i % 3) * .22f));
            float alpha = (float) Math.sin(flight * Math.PI) * .76f;
            drawSparkle(canvas, x, y, radius * (i % 3 == 0 ? .036f : .020f), i % 2 == 0 ? 0xffa1b879 : 0xffdfc366, alpha);
        }
        if (toStage <= fromStage) return;
        for (int i = 0; i < 5; i++) {
            float flight = segment(progress, .70f + i * .025f, .92f + i * .016f);
            if (flight <= 0f || flight >= 1f) continue;
            float angle = (float) (Math.PI * (1.12f + i * .18f));
            float travel = radius * (.55f + flight * .23f);
            float x = cx + (float) Math.cos(angle) * travel;
            float y = baseline - radius * .68f + (float) Math.sin(angle) * travel;
            drawSparkle(canvas, x, y, radius * (.033f + .012f * (i % 2)), 0xffe1bf61, (float) Math.sin(flight * Math.PI));
        }
    }

    private void drawSparkle(Canvas canvas, float x, float y, float size, int color, float alpha) {
        sparkle.reset();
        sparkle.moveTo(x, y - size);
        sparkle.quadTo(x + size * .18f, y - size * .18f, x + size, y);
        sparkle.quadTo(x + size * .18f, y + size * .18f, x, y + size);
        sparkle.quadTo(x - size * .18f, y + size * .18f, x - size, y);
        sparkle.quadTo(x - size * .18f, y - size * .18f, x, y - size);
        paint.setColor(color);
        paint.setAlpha(Math.round(255 * clamp(alpha)));
        canvas.drawPath(sparkle, paint);
        paint.setAlpha(255);
    }

    private void drawBitmap(Canvas canvas, Bitmap bitmap, float cx, float baseline, float maxW, float maxH, float alpha) {
        if (bitmap == null) return;
        fit(bitmap, cx, baseline, maxW, maxH);
        paint.setColor(0xffffffff);
        paint.setAlpha(Math.round(255 * clamp(alpha)));
        canvas.drawBitmap(bitmap, null, art, paint);
        paint.setAlpha(255);
    }

    private void fit(Bitmap bitmap, float cx, float baseline, float maxW, float maxH) {
        float scale = Math.min(maxW / bitmap.getWidth(), maxH / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        art.set(cx - width * .5f, baseline - height, cx + width * .5f, baseline);
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static float segment(float value, float start, float end) { return clamp((value - start) / (end - start)); }
    private static float smooth(float value) { return value * value * (3f - 2f * value); }
    private static float envelope(float value, float start, float end) {
        return (float) Math.sin(segment(value, start, end) * Math.PI);
    }
    private int dp(float value) { return Math.round(value * density); }
}
