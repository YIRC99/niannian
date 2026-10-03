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
    // Roots follow the open lawn in the courtyard artwork, four across and three deep.
    private static final float[] ROOT_X = {
            .165f, .390f, .615f, .840f,
            .185f, .410f, .635f, .855f,
            .160f, .385f, .610f, .835f
    };
    private static final float[] ROOT_Y = {
            .514f, .504f, .520f, .509f,
            .650f, .647f, .657f, .646f,
            .785f, .780f, .790f, .781f
    };
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
    private int headerClearance;
    private boolean hostActive = true;
    private float breezePhase;

    public GardenPlotView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        headerClearance = dp(112);
        backdrop = GardenArtCache.backdrop(getResources(), R.drawable.garden_courtyard_v3);
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

    /** Space, in dp, reserved for the host's title and sow button above the planting area. */
    public void setHeaderClearance(int clearanceDp) {
        headerClearance = dp(Math.max(0, clearanceDp));
        requestLayout();
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
            ArrayList<JSONObject> ordered = new ArrayList<>();
            for (JSONObject record : plants) if (record != null) ordered.add(record);
            ordered.sort((first, second) -> first.optString("task_id").compareTo(second.optString("task_id")));
            for (JSONObject record : ordered) {
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
        int preferredHeight = Math.max(dp(460), Math.min(dp(620), Math.round(width * 1.5f)));
        int height = resolveSize(preferredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
        updateSceneBounds(width, height);
        // Label space is reserved even when unselected, so selecting a plant never moves its roots.
        boolean sparse = planted.size() <= 4;
        int cellHeight = Math.max(dp(48), Math.round(sparse ? Math.min(height, scene.height()) * .226f
                : Math.min(height * .145f, scene.height() * .126f)));
        int cellWidth = Math.max(dp(48), Math.round(width * (sparse ? .285f : .218f)));
        for (PlantView plant : planted) {
            plant.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY));
            plant.updateLabelMetrics(cellWidth, cellHeight);
        }
    }

    private void updateSceneBounds(int width, int height) {
        float artWidth = backdrop == null ? 2f : backdrop.getWidth();
        float artHeight = backdrop == null ? 3f : backdrop.getHeight();
        float scale = Math.max(width / artWidth, height / artHeight);
        float sceneWidth = artWidth * scale, sceneHeight = artHeight * scale;
        scene.set((width - sceneWidth) / 2f, (height - sceneHeight) / 2f,
                (width + sceneWidth) / 2f, (height + sceneHeight) / 2f);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        updateSceneBounds(right - left, bottom - top);
        for (int i = 0; i < planted.size(); i++) {
            PlantView plant = planted.get(i);
            float x = getWidth() * rootX(i);
            float rootY = scene.top + scene.height() * rootY(i);
            float rootOffset = plant.getMeasuredHeight() - plant.labelHeight - dp(3);
            float y = Math.max(headerClearance, rootY - rootOffset);
            y = Math.min(y, getHeight() - plant.getMeasuredHeight() - dp(8));
            int childLeft = Math.round(x - plant.getMeasuredWidth() / 2f);
            int childTop = Math.round(y);
            plant.layout(childLeft, childTop, childLeft + plant.getMeasuredWidth(),
                    childTop + plant.getMeasuredHeight());
        }
    }

    private float rootX(int position) {
        if (planted.size() == 1) return .505f;
        if (planted.size() == 2) return position == 0 ? .355f : .655f;
        if (planted.size() == 3) return position == 0 ? .305f : position == 1 ? .665f : .475f;
        if (planted.size() == 4) return position % 2 == 0 ? .315f : .675f;
        return ROOT_X[position];
    }

    private float rootY(int position) {
        if (planted.size() == 1) return .674f;
        if (planted.size() == 2) return position == 0 ? .638f : .698f;
        if (planted.size() == 3) return position == 0 ? .608f : position == 1 ? .636f : .802f;
        if (planted.size() == 4) return position < 2 ? .612f + position * .014f : .804f - (position - 2) * .013f;
        return ROOT_Y[position];
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        sceneryPaint.setColor(0xffdae9be);
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
        private final float variation;
        private final float motionOffset;
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
            // Identity controls appearance, never completion count or the current selection.
            variation = .97f + Math.floorMod(taskId.hashCode(), 7) * .01f;
            motionOffset = Math.floorMod(taskId.hashCode(), 628) / 100f;
            art = GardenArtCache.plant(getResources(), stage, taskId);
            labelPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
            labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setColor(0xff405535);
            setClickable(true);
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setMinimumWidth(dp(48));
            setMinimumHeight(dp(48));
            String stageLabel = stage == 3 ? "已经开花" : stage == 2 ? "枝叶舒展"
                    : stage == 1 ? "小芽冒头" : "刚刚播种";
            setContentDescription((title.isEmpty() ? "未命名的小事" : title) + "，" + stageLabel
                    + "，已完成 " + record.optInt("completion_count") + " 次"
                    + (record.optBoolean("archived") ? "，已归档" : "，正在花园里生长") + "，点按选中");
            setOnClickListener(view -> {
                setSelectedPlantId(taskId);
                if (plantClickListener != null) plantClickListener.onPlantClick(this.record, this);
            });
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            updateLabelMetrics(width, height);
        }

        private void updateLabelMetrics(int width, int height) {
            shortTitle = TextUtils.ellipsize(title.isEmpty() ? "一件小事" : title,
                    labelPaint, Math.max(0, width - dp(18)), TextUtils.TruncateAt.END).toString();
            labelWidth = Math.min(width - dp(6), Math.max(dp(40), labelPaint.measureText(shortTitle) + dp(16)));
            Paint.FontMetrics metrics = labelPaint.getFontMetrics();
            labelHeight = Math.max(dp(20), metrics.descent - metrics.ascent + dp(6));
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
            float perspective = (.92f + (position / 4) * .035f) * variation;
            float soilWidth = getWidth() * .53f * perspective;
            paint.setStyle(Paint.Style.FILL);
            // The earth patch and selection ring stay rooted while leaves sway above them.
            if (isSelected() || isPressed() || isFocused()) {
                paint.setColor(isPressed() ? 0xa0eeeab0 : 0x66f4edbb);
                shapeBounds.set(centerX - soilWidth * .74f, rootY - dp(10),
                        centerX + soilWidth * .74f, rootY + dp(6));
                canvas.drawOval(shapeBounds, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.4f));
                paint.setColor(isFocused() ? 0xff587b49 : 0xffc8bb78);
                canvas.drawOval(shapeBounds, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(0x32677b37);
            shapeBounds.set(centerX - soilWidth * .57f, rootY - dp(4),
                    centerX + soilWidth * .57f, rootY + dp(5));
            canvas.drawOval(shapeBounds, paint);
            drawPlant(canvas, centerX, rootY, perspective);
            if (!isSelected()) return;
            // Only the selected plant carries a small wooden nameplate.
            paint.setColor(0xff9e8257);
            canvas.drawRect(centerX - labelWidth * .30f - dp(1), getHeight() - labelHeight - dp(3),
                    centerX - labelWidth * .30f + dp(1), getHeight() - dp(1), paint);
            canvas.drawRect(centerX + labelWidth * .30f - dp(1), getHeight() - labelHeight - dp(3),
                    centerX + labelWidth * .30f + dp(1), getHeight() - dp(1), paint);
            paint.setColor(0x30a18454);
            shapeBounds.set(centerX - labelWidth / 2f, getHeight() - labelHeight,
                    centerX + labelWidth / 2f, getHeight());
            canvas.drawRoundRect(shapeBounds, dp(3), dp(3), paint);
            shapeBounds.offset(0, -dp(1));
            paint.setColor(0xffffedc9);
            canvas.drawRoundRect(shapeBounds, dp(3), dp(3), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(.8f));
            paint.setColor(0xffbba174);
            canvas.drawRoundRect(shapeBounds, dp(3), dp(3), paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(shortTitle, centerX, labelBaseline, labelPaint);
        }

        private void drawPlant(Canvas canvas, float centerX, float baseline, float perspective) {
            if (art == null) return;
            float heightAvailable = baseline - dp(2);
            if (heightAvailable <= 0) return;
            float maxHeight = heightAvailable * perspective * (stage == 0 ? .53f : stage == 1 ? .80f : 1f);
            float maxWidth = getWidth() * .93f * perspective * (stage == 0 ? .67f : 1f);
            float scale = Math.min(maxWidth / art.getWidth(), maxHeight / art.getHeight());
            float width = art.getWidth() * scale, height = art.getHeight() * scale;
            artBounds.set(centerX - width / 2f, baseline - height, centerX + width / 2f, baseline);
            paint.setColor(0xffffffff);
            float angle = stage == 0 || !breeze.isStarted() ? 0f
                    : (float) Math.sin(breezePhase + motionOffset) * .85f;
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
