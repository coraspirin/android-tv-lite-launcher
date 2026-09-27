package local.kutu.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import java.io.File;

/**
 * Kutu Home's own settings, kept in the same "home" preferences file as the dock and
 * the theme: how many apps the shelf shows before it scrolls, the chosen background, and
 * the last reading of the remote's battery.
 */
final class HomeSettings {

    private static final String PREFS = "home";
    private static final String KEY_VISIBLE = "visible_tiles";
    private static final String KEY_BATTERY = "remote_battery_pct";
    private static final String KEY_BATTERY_AT = "remote_battery_at";
    private static final String KEY_BATTERY_TRIED = "remote_battery_tried_at";
    private static final String KEY_BG_SOURCE = "background_source";

    /** guide 19: about seven slots; the settings screen allows five to nine */
    static final int VISIBLE_DEFAULT = 7;
    static final int VISIBLE_MIN = 5;
    static final int VISIBLE_MAX = 9;

    /** The chosen background, already cropped to the screen, in Kutu Home's private files. */
    private static final String BACKGROUND_FILE = "background.jpg";

    private HomeSettings() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int visibleTiles(Context ctx) {
        int n = prefs(ctx).getInt(KEY_VISIBLE, VISIBLE_DEFAULT);
        return Math.max(VISIBLE_MIN, Math.min(VISIBLE_MAX, n));
    }

    static void setVisibleTiles(Context ctx, int n) {
        prefs(ctx).edit().putInt(KEY_VISIBLE, Math.max(VISIBLE_MIN, Math.min(VISIBLE_MAX, n))).apply();
    }

    // ------------------------------------------------------------ background

    static File backgroundFile(Context ctx) {
        return new File(ctx.getFilesDir(), BACKGROUND_FILE);
    }

    static boolean hasCustomBackground(Context ctx) {
        File f = backgroundFile(ctx);
        return f.isFile() && f.length() > 0;
    }

    static void clearBackground(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        backgroundFile(ctx).delete();
        setBackgroundSource(ctx, null);
    }

    /** name of the photo the current background was made from, so the picker can mark it */
    static String backgroundSource(Context ctx) {
        return hasCustomBackground(ctx) ? prefs(ctx).getString(KEY_BG_SOURCE, null) : null;
    }

    static void setBackgroundSource(Context ctx, String name) {
        prefs(ctx).edit().putString(KEY_BG_SOURCE, name).apply();
    }

    /**
     * Shows the chosen background, or the bundled one. The file is decoded once per
     * screen; it was already scaled to the display when it was chosen, so this is a plain
     * 1080p JPEG decode and nothing runs afterwards (guide 17: no continuous processing).
     */
    static void applyBackground(Context ctx, ImageView view) {
        if (hasCustomBackground(ctx)) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(backgroundFile(ctx).getAbsolutePath());
                if (bmp != null) {
                    Drawable d = new BitmapDrawable(ctx.getResources(), bmp);
                    view.setImageDrawable(d);
                    return;
                }
            } catch (OutOfMemoryError | Exception ignored) {
            }
        }
        view.setImageResource(R.drawable.kutu_home_background);
    }

    // ------------------------------------------------------------ Wi-Fi name

    private static final String KEY_ASKED_LOCATION = "asked_location_v1";

    /** Android 9 shows the Wi-Fi network's name only to an app with location access. */
    static boolean canReadWifiName(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** The launcher asks for it by itself only once; after that only the settings row asks. */
    static boolean askedLocation(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ASKED_LOCATION, false);
    }

    static void setAskedLocation(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_ASKED_LOCATION, true).apply();
    }

    // ------------------------------------------------------------ remote battery

    /** @return last successful reading 0-100, or -1 when none has succeeded */
    static int remoteBattery(Context ctx) {
        return prefs(ctx).getInt(KEY_BATTERY, -1);
    }

    /** wall-clock time of the last successful reading */
    static long remoteBatteryAt(Context ctx) {
        return prefs(ctx).getLong(KEY_BATTERY_AT, 0L);
    }

    static void setRemoteBattery(Context ctx, int pct, long at) {
        prefs(ctx).edit().putInt(KEY_BATTERY, pct).putLong(KEY_BATTERY_AT, at).apply();
    }

    /**
     * Wall-clock time of the last attempt, successful or not. A BLE remote sleeps between
     * key presses, so an attempt can fail harmlessly; this keeps a failure from being
     * retried on every return to the home screen.
     */
    static long remoteBatteryTriedAt(Context ctx) {
        return prefs(ctx).getLong(KEY_BATTERY_TRIED, 0L);
    }

    static void setRemoteBatteryTriedAt(Context ctx, long at) {
        prefs(ctx).edit().putLong(KEY_BATTERY_TRIED, at).apply();
    }
}
