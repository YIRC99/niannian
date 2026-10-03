package dev.niannian.reminder;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.View;

import java.util.Arrays;

/** A decorative garden whose visible plants come only from the supplied task stages. */
public final class GardenSceneView extends View {
    private static final int MAX_PLANTS = 6;
    private static final int MAX_BITMAP_EDGE = 384;
    private static final LruCache<Integer, Bitmap> ART = new LruCache<Integer, Bitmap>(3 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path ground = new Path();
    private final RectF bounds = new RectF();
    private final Bitmap[] plants = new Bitmap[4];
    private final Bitmap watering;
    private final float density;
    private int[] stages = new int[0];

    public GardenSceneView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
        setWillNotDraw(false);
        watering = loadArt(getResources(), R.drawable.garden_watering);
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
                int resource = stage == 0 ? R.drawable.garden_seed : stage == 1 ? R.drawable.garden_sprout
                        : stage == 2 ? R.drawable.garden_leaf : R.drawable.garden_bloom;
                plants[stage] = loadArt(getResources(), resource);
            }
        }
        invalidate();
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
            drawArt(canvas, plants[stage], x, baseline, artWidth * (stage == 0 ? .72f : 1f),
                    artHeight * (stage == 0 ? .62f : stage == 1 ? .82f : stage == 2 ? .94f : 1f));
        }
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

    /** Decode once, without density inflation; keep a bounded shared cache across screen renders. */
    private static Bitmap loadArt(Resources resources, int resource) {
        synchronized (ART) {
            Bitmap cached = ART.get(resource);
            if (cached != null) return cached;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            options.inScaled = false;
            BitmapFactory.decodeResource(resources, resource, options);
            int longest = Math.max(options.outWidth, options.outHeight);
            options.inSampleSize = 1;
            while (longest / options.inSampleSize > MAX_BITMAP_EDGE) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeResource(resources, resource, options);
            if (bitmap != null) {
                bitmap = trimTransparentEdges(bitmap);
                ART.put(resource, bitmap);
            }
            return bitmap;
        }
    }

    private static Bitmap trimTransparentEdges(Bitmap source) {
        int width = source.getWidth(), height = source.getHeight();
        int[] row = new int[width];
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) {
            source.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if ((row[x] >>> 24) > 12) {
                    left = Math.min(left, x); right = Math.max(right, x);
                    top = Math.min(top, y); bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < left || (left == 0 && top == 0 && right == width - 1 && bottom == height - 1)) return source;
        Bitmap cropped = Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1);
        if (cropped != source) source.recycle();
        return cropped;
    }

    private int dp(float value) { return Math.round(value * density); }
}
