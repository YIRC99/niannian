package dev.niannian.reminder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One garden bed. Only supplied records become plants; the host owns bed pagination and details. */
public final class GardenPlotView extends FrameLayout {
    public interface OnPlantClickListener {
        void onPlantClick(JSONObject plant, View trigger);
    }

    private static final int CAPACITY = 12;
    private final Paint sceneryPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF scene = new RectF();
    private final Rect visible = new Rect();
    private final Bitmap backdrop;
    private final float density;
    private final ArrayList<PlantView> planted = new ArrayList<>();
    private final ValueAnimator breeze;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::updateMotion;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateMotion;
    private OnPlantClickListener plantClickListener;
    private String selectedPlantId = "";
    private boolean hostActive = true;
    private float breezePhase;

    public GardenPlotView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        backdrop = GardenArtCache.backdrop(getResources(), R.drawable.garden_backdrop_v2);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        breeze = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        breeze.setDuration(9200L);
        breeze.setRepeatCount(ValueAnimator.INFINITE);
        breeze.setInterpolator(new LinearInterpolator());
        breeze.addUpdateListener(animation -> {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                stopBreeze();
                return;
            }
            breezePhase = (float) animation.getAnimatedValue();
            for (PlantView plant : planted) if (plant.stage > 0) plant.invalidate();
        });
    }

    public void setOnPlantClickListener(OnPlantClickListener listener) {
        plantClickListener = listener;
    }

    public void setPlants(JSONArray plants) {
        ArrayList<JSONObject> records = new ArrayList<>();
        if (plants != null) {
            for (int i = 0; i < plants.length() && records.size() < CAPACITY; i++) {
                JSONObject plant = plants.optJSONObject(i);
                if (plant != null) records.add(plant);
            }
        }
        setPlants(records);
    }

    /** The host passes the current bed only; plants are never synthesized from totals or stages. */
    public void setPlants(List<JSONObject> plants) {
        stopBreeze();
        removeAllViews();
        planted.clear();
        if (plants != null) {
            for (JSONObject record : plants) {
                if (record == null) continue;
                if (planted.size() == CAPACITY) break;
                PlantView plant = new PlantView(getContext(), record, planted.size());
                plant.setSelected(!selectedPlantId.isEmpty() && selectedPlantId.equals(plant.taskId));
                planted.add(plant);
                addView(plant, new FrameLayout.LayoutParams(dp(80), dp(80)));
            }
        }
        requestLayout();
        updateMotion();
    }

    public void setSelectedPlantId(String taskId) {
        selectedPlantId = taskId == null ? "" : taskId;
        for (PlantView plant : planted) {
            plant.setSelected(!selectedPlantId.isEmpty() && selectedPlantId.equals(plant.taskId));
        }
    }

    public void setHostActive(boolean active) {
        hostActive = active;
        updateMotion();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(dp(360), widthMeasureSpec);
        int preferredHeight = Math.max(dp(460), Math.min(dp(530), Math.round(width * 4f / 3f)));
        int height = resolveSize(preferredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
        updateSceneBounds(width, height);
        // Each row owns its entire touch area. The visible illustration is smaller than that area.
        int cellHeight = Math.max(dp(48), Math.round(scene.height() * .153f));
        for (PlantView plant : planted) {
            // The front corners belong to the watering can and empty pot in the scenery.
            float widthFraction = plant.position / 3 == 3 ? .215f : .285f;
            int cellWidth = Math.max(dp(48), Math.round(scene.width() * widthFraction));
            plant.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY));
        }
    }

    private void updateSceneBounds(int width, int height) {
        float artWidth = backdrop == null ? 3f : backdrop.getWidth();
        float artHeight = backdrop == null ? 4f : backdrop.getHeight();
        float scale = Math.min(width / artWidth, height / artHeight);
        float sceneWidth = artWidth * scale, sceneHeight = artHeight * scale;
        scene.set((width - sceneWidth) / 2f, (height - sceneHeight) / 2f,
                (width + sceneWidth) / 2f, (height + sceneHeight) / 2f);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        updateSceneBounds(right - left, bottom - top);
        for (int i = 0; i < planted.size(); i++) {
            PlantView plant = planted.get(i);
            int row = i / 3, column = i % 3;
            float stagger = row % 2 == 0 ? -.014f : .014f;
            float xFraction = row == 3 ? .28f + column * .22f : .215f + column * .285f + stagger;
            float x = scene.left + scene.width() * xFraction;
            float y = scene.top + scene.height() * (.278f + row * .157f);
            int childLeft = Math.round(x - plant.getMeasuredWidth() / 2f);
            int childTop = Math.round(y);
            plant.layout(childLeft, childTop, childLeft + plant.getMeasuredWidth(),
                    childTop + plant.getMeasuredHeight());
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        sceneryPaint.setColor(0xffdeebc8);
        canvas.drawRect(0, 0, getWidth(), getHeight(), sceneryPaint);
        if (backdrop != null) canvas.drawBitmap(backdrop, null, scene, sceneryPaint);
    }

    private void updateMotion() {
        if (breeze == null) return;
        boolean hasLeaves = false;
        for (PlantView plant : planted) if (plant.stage > 0) { hasLeaves = true; break; }
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
            for (PlantView plant : planted) plant.invalidate();
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

    private final class PlantView extends View {
        private final JSONObject record;
        private final String taskId;
        private final String title;
        private final int stage;
        private final int position;
        private final Bitmap art;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF artBounds = new RectF();
        private final RectF shapeBounds = new RectF();
        private String shortTitle = "";
        private float labelWidth;
        private float labelHeight;
        private float labelBaseline;

        PlantView(Context context, JSONObject record, int position) {
            super(context);
            this.record = record;
            this.position = position;
            taskId = record.optString("task_id");
            title = record.optString("title").trim();
            String stageName = record.optString("stage");
            stage = "bloom".equals(stageName) ? 3 : "leaf".equals(stageName) ? 2
                    : "sprout".equals(stageName) ? 1 : 0;
            // Flower variety never changes growth stage and remains stable across bed pagination.
            int variant = Math.floorMod(taskId.hashCode(), 3);
            art = stage == 3 && variant != 0
                    ? GardenArtCache.load(getResources(), variant == 1
                    ? R.drawable.garden_bloom_pink_v2 : R.drawable.garden_bloom_lilac_v2)
                    : GardenArtCache.plant(getResources(), stage);
            labelPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
            labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setColor(0xff425b38);
            setClickable(true);
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setMinimumWidth(dp(48));
            setMinimumHeight(dp(48));
            String stageLabel = stage == 3 ? "已经开花" : stage == 2 ? "枝叶舒展"
                    : stage == 1 ? "小芽冒头" : "刚刚播种";
            setContentDescription((title.isEmpty() ? "未命名的小事" : title) + "，" + stageLabel
                    + "，" + record.optInt("completion_count") + " 份养分"
                    + (record.optBoolean("archived") ? "，已归档" : "，正在花园里生长") + "，点按查看");
            setOnClickListener(view -> {
                setSelectedPlantId(taskId);
                if (plantClickListener != null) plantClickListener.onPlantClick(this.record, this);
            });
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            shortTitle = TextUtils.ellipsize(title.isEmpty() ? "一件小事" : title,
                    labelPaint, Math.max(0, width - dp(18)), TextUtils.TruncateAt.END).toString();
            labelWidth = Math.min(width - dp(6), labelPaint.measureText(shortTitle) + dp(14));
            Paint.FontMetrics metrics = labelPaint.getFontMetrics();
            labelHeight = Math.max(dp(18), metrics.descent - metrics.ascent + dp(5));
            labelBaseline = height - labelHeight / 2f - (metrics.ascent + metrics.descent) / 2f - dp(1);
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            info.setSelected(isSelected());
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() / 2f;
            float rootY = getHeight() - labelHeight - dp(3);
            float perspective = .84f + (position / 3) * .055f;
            float soilWidth = getWidth() * .53f * perspective;
            paint.setStyle(Paint.Style.FILL);
            // The earth patch and selection ring stay rooted while leaves sway above them.
            if (isSelected() || isPressed() || isFocused()) {
                paint.setColor(isPressed() ? 0xa0fff1a1 : 0x88fff5b6);
                shapeBounds.set(centerX - soilWidth * .74f, rootY - dp(10),
                        centerX + soilWidth * .74f, rootY + dp(6));
                canvas.drawOval(shapeBounds, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.4f));
                paint.setColor(0xffe5c76e);
                canvas.drawOval(shapeBounds, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(0x3661843e);
            shapeBounds.set(centerX - soilWidth * .57f, rootY - dp(4),
                    centerX + soilWidth * .57f, rootY + dp(5));
            canvas.drawOval(shapeBounds, paint);
            paint.setColor(0xffc7a471);
            shapeBounds.set(centerX - soilWidth / 2f, rootY - dp(7),
                    centerX + soilWidth / 2f, rootY + dp(1));
            canvas.drawOval(shapeBounds, paint);
            paint.setColor(0xffe0be86);
            shapeBounds.set(centerX - soilWidth * .45f, rootY - dp(7),
                    centerX + soilWidth * .45f, rootY - dp(1));
            canvas.drawOval(shapeBounds, paint);
            drawPlant(canvas, centerX, rootY, perspective);

            paint.setColor(0x20a18454);
            shapeBounds.set(centerX - labelWidth / 2f, getHeight() - labelHeight,
                    centerX + labelWidth / 2f, getHeight());
            canvas.drawRoundRect(shapeBounds, dp(6), dp(6), paint);
            shapeBounds.offset(0, -dp(1));
            paint.setColor(isSelected() ? 0xffffefbb : 0xf2fffbed);
            canvas.drawRoundRect(shapeBounds, dp(6), dp(6), paint);
            canvas.drawText(shortTitle, centerX, labelBaseline, labelPaint);
        }

        private void drawPlant(Canvas canvas, float centerX, float baseline, float perspective) {
            if (art == null) return;
            float heightAvailable = baseline - dp(2);
            if (heightAvailable <= 0) return;
            float maxHeight = heightAvailable * perspective * (stage == 0 ? .49f : stage == 1 ? .78f : 1f);
            float maxWidth = getWidth() * .78f * perspective * (stage == 0 ? .63f : 1f);
            float scale = Math.min(maxWidth / art.getWidth(), maxHeight / art.getHeight());
            float width = art.getWidth() * scale, height = art.getHeight() * scale;
            artBounds.set(centerX - width / 2f, baseline - height, centerX + width / 2f, baseline);
            paint.setColor(0xffffffff);
            float angle = stage == 0 || !breeze.isStarted() ? 0f
                    : (float) Math.sin(breezePhase + position * 1.71f) * .9f;
            if (angle == 0f) {
                canvas.drawBitmap(art, null, artBounds, paint);
                return;
            }
            float root = baseline - height * (stage == 1 ? .23f : .17f);
            canvas.save();
            canvas.clipRect(artBounds.left - dp(3), root, artBounds.right + dp(3), baseline + dp(1));
            canvas.drawBitmap(art, null, artBounds, paint);
            canvas.restore();
            canvas.save();
            canvas.clipRect(artBounds.left - dp(3), artBounds.top - dp(3), artBounds.right + dp(3), root + dp(.7f));
            canvas.rotate(angle, centerX, root);
            canvas.drawBitmap(art, null, artBounds, paint);
            canvas.restore();
        }
    }

    private int dp(float value) { return Math.round(value * density); }
}
