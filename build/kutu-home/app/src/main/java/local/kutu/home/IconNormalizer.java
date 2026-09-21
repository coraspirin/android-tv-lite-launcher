package local.kutu.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * Guide 18: normalise every app icon to a consistent rounded-square card.
 *
 *  - adaptive icons are rendered layer by layer and cropped ourselves, so the
 *    firmware's own mask never applies and nothing gets circle-cropped
 *  - a logo that is mostly transparent gets a near-black inner background
 *  - the result is a plain bitmap; no tint is ever applied, so a tile's focus
 *    glow cannot bleed into the resting icon
 */
final class IconNormalizer {

    /** Adaptive icons reserve the outer 1/6 on each side as bleed. */
    private static final float ADAPTIVE_ZOOM = 1.5f;
    private static final float CORNER_FRACTION = 0.22f;
    private static final float TRANSPARENCY_THRESHOLD = 0.35f;

    private static final LruCache<String, Drawable> CACHE = new LruCache<>(64);

    private IconNormalizer() {
    }

    static Drawable normalize(Context ctx, String key, Drawable src, int sizePx) {
        String cacheKey = key + "@" + sizePx;
        Drawable hit = CACHE.get(cacheKey);
        if (hit != null) return hit;

        Drawable made = render(ctx, src, sizePx);
        if (made != null) CACHE.put(cacheKey, made);
        return made;
    }

    static void clearCache() {
        CACHE.evictAll();
    }

    private static Drawable render(Context ctx, Drawable src, int size) {
        if (size <= 0) return src;

        Bitmap card = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(card);

        float radius = size * CORNER_FRACTION;
        Path clip = new Path();
        clip.addRoundRect(new RectF(0f, 0f, size, size), radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        if (src instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) src;
            int big = Math.round(size * ADAPTIVE_ZOOM);
            Bitmap full = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888);
            Canvas fullCanvas = new Canvas(full);

            Drawable bg = adaptive.getBackground();
            Drawable fg = adaptive.getForeground();
            if (bg != null) {
                bg.setBounds(0, 0, big, big);
                bg.draw(fullCanvas);
            } else {
                fullCanvas.drawColor(ctx.getColor(R.color.tile_transparent_logo_bg));
            }
            if (fg != null) {
                fg.setBounds(0, 0, big, big);
                fg.draw(fullCanvas);
            }

            int inset = (big - size) / 2;
            canvas.drawBitmap(full, new Rect(inset, inset, inset + size, inset + size),
                    new Rect(0, 0, size, size), paint);
            full.recycle();
            return new BitmapDrawable(ctx.getResources(), card);
        }

        Bitmap flat = toBitmap(src, size);
        if (flat == null) {
            canvas.drawColor(ctx.getColor(R.color.tile_transparent_logo_bg));
            return new BitmapDrawable(ctx.getResources(), card);
        }

        if (isMostlyTransparent(flat)) {
            // a transparent logo needs a surface to sit on (guide 18)
            canvas.drawColor(ctx.getColor(R.color.tile_transparent_logo_bg));
            float pad = size * 0.16f;
            RectF dst = fitCentre(flat.getWidth(), flat.getHeight(), pad, size - pad);
            canvas.drawBitmap(flat, null, dst, paint);
        } else {
            RectF dst = fitCentre(flat.getWidth(), flat.getHeight(), 0f, size);
            canvas.drawBitmap(flat, null, dst, paint);
        }
        flat.recycle();
        return new BitmapDrawable(ctx.getResources(), card);
    }

    /** Aspect-preserving fit into the square [lo, hi]; never stretches. */
    private static RectF fitCentre(int w, int h, float lo, float hi) {
        float box = hi - lo;
        if (w <= 0 || h <= 0) return new RectF(lo, lo, hi, hi);
        float scale = Math.min(box / w, box / h);
        float dw = w * scale, dh = h * scale;
        float left = lo + (box - dw) / 2f;
        float top = lo + (box - dh) / 2f;
        return new RectF(left, top, left + dw, top + dh);
    }

    private static Bitmap toBitmap(Drawable d, int size) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) return b.copy(Bitmap.Config.ARGB_8888, false);
        }
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : size;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : size;
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** Cheap sampled alpha scan; avoids reading every pixel of a large icon. */
    private static boolean isMostlyTransparent(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0) return true;
        int stepX = Math.max(1, w / 24), stepY = Math.max(1, h / 24);
        int seen = 0, clear = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                seen++;
                if (Color.alpha(b.getPixel(x, y)) < 16) clear++;
            }
        }
        return seen > 0 && ((float) clear / seen) > TRANSPARENCY_THRESHOLD;
    }
}
