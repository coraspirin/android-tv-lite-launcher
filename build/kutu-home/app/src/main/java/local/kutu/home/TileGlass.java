package local.kutu.home;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.LruCache;
import android.util.StateSet;
import android.util.TypedValue;
import android.view.View;

/**
 * Rings a focused tile in the colour of the icon sitting on it.
 *
 * Colour appears on one tile at a time: the focused card gets a thin outline in its
 * icon's own hue, a haze fading inwards from it, and a face tinted just enough to
 * belong to them. Every resting card stays colourless glass behind a plain hairline.
 * Tinting all seven at once turned the shelf into a row of competing colours and cost
 * the focused tile the thing that made it stand out.
 *
 * Only the hue, and roughly how vivid the artwork is, are taken from the icon.
 * Brightness is thrown away and replaced with a fixed light value, because most TV
 * icons are dark cards - HBO Max, Apple TV, TV+, tabii - and a near-black ring would
 * read as a hole rather than as that app's colour.
 *
 * An icon with no usable colour falls back to white, which gives the plain bright edge
 * the design had before. The resting face is white whatever the icon, so a resting tile
 * is pixel-identical to the neutral {@code tile_bg} in XML. That drawable stays the
 * background both tile layouts are inflated with, and is what an unavailable app keeps.
 */
final class TileGlass {

    /** Below this there is not enough agreed-on colour in the icon to be worth showing. */
    private static final float MIN_CHROMA = 0.10f;
    /**
     * A pixel has to be this colourful to get a vote at all. Muted fields are what these
     * icons are mostly made of, and they outnumber a brand mark badly enough to win on
     * sheer count however the votes are weighted. Worse, when the two disagree - TV+ is a
     * yellow mark on a navy field, nearly opposite hues - they cancel and the ring comes
     * out white. Excluding the field outright lets the mark speak for the icon.
     */
    private static final float MIN_PIXEL_SAT = 0.45f;
    /**
     * The ring is a thin line with nothing sitting on it to stay legible against, so it
     * can carry far more colour than a filled surface could.
     */
    private static final float MAX_RING_SAT = 0.85f;
    /**
     * A floor, so an icon that has a colour at all gets a ring that reads as that colour.
     * Brand palettes vary far more than a focus indicator should: TV+ is a small yellow
     * mark on a big muted navy field, and reproducing that field's own saturation gave a
     * ring so pale it barely registered next to Stremio's purple.
     */
    private static final float MIN_RING_SAT = 0.50f;
    /** Chroma is a small number by construction; this brings a clear logo up to the cap. */
    private static final float CHROMA_GAIN = 2.2f;
    /** Held just below full so a yellow or cyan ring is not blinding on a bright panel. */
    private static final float RING_VALUE = 0.95f;

    // The resting face is colourless; alphas match drawable/tile_bg.xml.
    private static final int REST_FACE_TOP_A = 0x59;
    private static final int REST_FACE_BOTTOM_A = 0x26;
    /** The focused face is barely tinted - enough to belong to the ring, not to compete. */
    private static final float FOCUS_FACE_MIX = 0.20f;
    private static final int FOCUS_FACE_TOP_A = 0xF2;
    private static final int FOCUS_FACE_BOTTOM_A = 0xBF;

    private static final float REST_EDGE_DP = 1f;
    /** The focused outline. Thin and crisp; the depth comes from the haze inside it. */
    private static final int FOCUS_EDGE_A = 0xFF;
    private static final float FOCUS_EDGE_DP = 1.5f;

    /**
     * Concentric rings just inside the outline, fading as they go in, which is what reads
     * as the dusty inward gradient. Drawn as rings rather than as a radial gradient
     * because a GradientDrawable's radial radius is set in pixels and the card's size is
     * not known when the background is built. Each is one dp wide and they do not
     * overlap, so the alphas below are what lands on screen rather than accumulating.
     */
    private static final int[] HAZE_ALPHA = {0x59, 0x42, 0x30, 0x21, 0x15, 0x0C, 0x06};

    private static final LruCache<String, Integer> ACCENT = new LruCache<>(64);

    private TileGlass() {
    }

    /** Gives the card a focus ring in its icon's colour; the resting state is unchanged. */
    static void apply(View card, String key, Drawable icon) {
        if (card == null) return;
        card.setBackground(build(card.getResources(), accentOf(key, icon)));
    }

    static void clearCache() {
        ACCENT.evictAll();
    }

    private static int accentOf(String key, Drawable icon) {
        Integer hit = ACCENT.get(key);
        if (hit != null) return hit;
        int accent = extract(icon);
        ACCENT.put(key, accent);
        return accent;
    }

    /**
     * The dominant hue of an icon, as a light, moderately saturated colour.
     *
     * Hue is an angle, so it is averaged as a vector rather than arithmetically: a red
     * logo whose pixels sit at 359 and 1 degrees must not average out to cyan. Each
     * pixel is weighted by how vivid it is, so the black plate behind a logo pulls the
     * result in no direction at all.
     */
    private static int extract(Drawable d) {
        if (!(d instanceof BitmapDrawable)) return Color.WHITE;
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        if (b == null || b.isRecycled()) return Color.WHITE;

        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0) return Color.WHITE;

        // Same sampled scan IconNormalizer uses; reading every pixel of a large icon is
        // not worth it for a number this coarse.
        int stepX = Math.max(1, w / 24), stepY = Math.max(1, h / 24);

        double x = 0, y = 0, weight = 0, satSum = 0;
        float[] hsv = new float[3];
        for (int py = 0; py < h; py += stepY) {
            for (int px = 0; px < w; px += stepX) {
                int p = b.getPixel(px, py);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < MIN_PIXEL_SAT) continue;   // a muted field gets no vote
                // Saturation is squared so a small, vivid brand mark can outvote a large
                // muted field. Weighting by area alone hands the hue to whatever fills
                // the icon, which on these cards is usually just the dark plate.
                double vivid = hsv[1] * hsv[1] * hsv[2];
                if (vivid <= 0) continue;          // black, white and grey say nothing
                double rad = Math.toRadians(hsv[0]);
                x += vivid * Math.cos(rad);
                y += vivid * Math.sin(rad);
                satSum += vivid * hsv[1];
                weight += vivid;
            }
        }
        if (weight <= 0) return Color.WHITE;

        // How far the hues agreed. A logo built from clashing colours - the Play Store's
        // four, a photographic banner - cancels itself out and is left neutral instead of
        // being washed in whichever colour narrowly won.
        double agreement = Math.hypot(x, y) / weight;
        float chroma = (float) ((satSum / weight) * agreement);
        if (chroma < MIN_CHROMA) return Color.WHITE;

        float hue = (float) Math.toDegrees(Math.atan2(y, x));
        if (hue < 0) hue += 360f;
        float sat = Math.max(MIN_RING_SAT, Math.min(MAX_RING_SAT, chroma * CHROMA_GAIN));
        return Color.HSVToColor(new float[]{hue, sat, RING_VALUE});
    }

    /**
     * Both surfaces are built from resources rather than from a hardcoded white, so the
     * cards follow the -night qualifier like every other glass surface does. This class
     * is the only place that draws glass from code, so it is also the only place that
     * would otherwise have stayed bright white in the dark theme.
     */
    private static Drawable build(Resources res, int ring) {
        float radius = res.getDimensionPixelSize(R.dimen.tile_radius);
        int base = res.getColor(R.color.tile_glass_base, null);
        int edge = res.getColor(R.color.glass_edge, null);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused(res, radius, ring, base));
        states.addState(StateSet.WILD_CARD,
                face(radius, base, REST_FACE_TOP_A, REST_FACE_BOTTOM_A,
                        dp(res, REST_EDGE_DP), edge));
        return states;
    }

    /** The tinted face, a thin outline in the icon's colour, and the haze inside it. */
    private static Drawable focused(Resources res, float radius, int ring, int base) {
        int step = dp(res, 1f);
        Drawable[] layers = new Drawable[2 + HAZE_ALPHA.length];

        // Layer 0 is the only full-bounds layer, so it is the one LayerDrawable takes the
        // view's outline from - which is what shapes the focus lift's shadow.
        layers[0] = face(radius, mix(base, ring, FOCUS_FACE_MIX),
                FOCUS_FACE_TOP_A, FOCUS_FACE_BOTTOM_A, 0, Color.TRANSPARENT);
        layers[1] = outline(radius, dp(res, FOCUS_EDGE_DP), withAlpha(ring, FOCUS_EDGE_A));
        for (int i = 0; i < HAZE_ALPHA.length; i++) {
            layers[2 + i] = outline(radius - (i + 1) * step, step,
                    withAlpha(ring, HAZE_ALPHA[i]));
        }

        LayerDrawable stack = new LayerDrawable(layers);
        for (int i = 0; i < HAZE_ALPHA.length; i++) {
            int inset = (i + 1) * step;
            stack.setLayerInset(2 + i, inset, inset, inset, inset);
        }
        return stack;
    }

    /**
     * A filled rounded rect. GradientDrawable insets its own bounds by half the stroke,
     * so an outline sits inside the card rather than spilling past it.
     */
    private static GradientDrawable face(float radius, int rgb, int topAlpha, int bottomAlpha,
                                         int strokeWidth, int strokeColor) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(rgb, topAlpha), withAlpha(rgb, bottomAlpha)});
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        if (strokeWidth > 0) g.setStroke(strokeWidth, strokeColor);
        g.setDither(true);
        return g;
    }

    /** An unfilled rounded rect: one ring of the border or of the haze behind it. */
    private static GradientDrawable outline(float radius, int width, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(Math.max(0f, radius));
        g.setStroke(width, color);
        return g;
    }

    /**
     * Blends the glass base towards the ring colour: k=0 leaves the base, k=1 is the ring
     * itself. The base is passed in rather than assumed white, which is what lets the
     * focused card tint correctly over dark glass as well as light.
     */
    private static int mix(int base, int rgb, float k) {
        return Color.rgb(
                Math.round(Color.red(base) + (Color.red(rgb) - Color.red(base)) * k),
                Math.round(Color.green(base) + (Color.green(rgb) - Color.green(base)) * k),
                Math.round(Color.blue(base) + (Color.blue(rgb) - Color.blue(base)) * k));
    }

    private static int withAlpha(int rgb, int a) {
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    private static int dp(Resources res, float value) {
        return Math.max(1, Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, res.getDisplayMetrics())));
    }
}
