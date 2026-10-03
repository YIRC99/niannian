package dev.niannian.reminder;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

/** Small, density-independent artwork shared by the garden and its growth ceremony. */
final class GardenArtCache {
    private static final int MAX_EDGE = 512;
    private static final LruCache<Integer, Bitmap> ART = new LruCache<Integer, Bitmap>(5 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };
    private static final LruCache<Integer, Bitmap> SCENES = new LruCache<Integer, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };

    private GardenArtCache() { }

    static Bitmap plant(Resources resources, int stage) {
        return load(resources, stage == 0 ? R.drawable.garden_seed : stage == 1 ? R.drawable.garden_sprout
                : stage == 2 ? R.drawable.garden_leaf : R.drawable.garden_bloom);
    }

    static Bitmap load(Resources resources, int resource) {
        synchronized (ART) {
            Bitmap cached = ART.get(resource);
            if (cached != null) return cached;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            options.inScaled = false;
            BitmapFactory.decodeResource(resources, resource, options);
            int longest = Math.max(options.outWidth, options.outHeight);
            options.inSampleSize = 1;
            while (longest / options.inSampleSize > MAX_EDGE) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeResource(resources, resource, options);
            if (bitmap != null) {
                bitmap = trim(bitmap);
                ART.put(resource, bitmap);
            }
            return bitmap;
        }
    }

    /** Scenery preserves its full composition and is never decoded through the small icon cache. */
    static Bitmap backdrop(Resources resources, int resource) {
        synchronized (SCENES) {
            Bitmap cached = SCENES.get(resource);
            if (cached != null) return cached;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            options.inScaled = false;
            BitmapFactory.decodeResource(resources, resource, options);
            int longest = Math.max(options.outWidth, options.outHeight);
            options.inSampleSize = 1;
            while (longest / options.inSampleSize > 1536) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeResource(resources, resource, options);
            if (bitmap != null) SCENES.put(resource, bitmap);
            return bitmap;
        }
    }

    private static Bitmap trim(Bitmap source) {
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
}
