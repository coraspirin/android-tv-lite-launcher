package local.kutu.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Calendar;

/**
 * The launcher's own light/dark setting, and the one mechanism that applies it.
 *
 * Guide 15 rules out AndroidX, so {@code AppCompatDelegate.setDefaultNightMode} is not
 * available, and {@code UiModeManager.setNightMode} needs the signature-level
 * MODIFY_DAY_NIGHT_MODE permission, so the system's own setting cannot be driven from
 * here either. What remains is what AppCompat does internally: override the activity's
 * uiMode before its resources are resolved, and let the platform pick -night qualified
 * resources. Every drawable and layout already reads @color tokens, so values-night
 * re-skins the whole launcher without touching any of them.
 *
 * Three modes: light, dark, and automatic, which is dark between two times of day the
 * human sets in Kutu Home's settings. Automatic needs no timer of its own: HomeActivity
 * already wakes on ACTION_TIME_TICK for the clock and re-checks {@link #isDark} there.
 *
 * Shares DockStore's preferences file; this is the same "home" store, a few more keys.
 */
final class ThemeStore {

    static final int MODE_LIGHT = 0;
    static final int MODE_DARK = 1;
    static final int MODE_AUTO = 2;

    private static final String PREFS = "home";
    private static final String KEY_MODE = "theme_mode_v2";
    /** version 1 stored a plain boolean; read once so an update keeps the human's choice */
    private static final String KEY_DARK_V1 = "theme_dark_v1";
    private static final String KEY_DARK_FROM = "theme_dark_from_min";
    private static final String KEY_LIGHT_FROM = "theme_light_from_min";

    static final int DEFAULT_DARK_FROM = 19 * 60;
    static final int DEFAULT_LIGHT_FROM = 7 * 60;
    /** the settings screen moves the automatic times in steps of this many minutes */
    static final int STEP_MIN = 30;

    private ThemeStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Light is the default: it is the design the launcher shipped with. */
    static int mode(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.contains(KEY_MODE)) return p.getInt(KEY_MODE, MODE_LIGHT);
        return p.getBoolean(KEY_DARK_V1, false) ? MODE_DARK : MODE_LIGHT;
    }

    static void setMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(KEY_MODE, mode).remove(KEY_DARK_V1).apply();
    }

    static int darkFrom(Context ctx) {
        return prefs(ctx).getInt(KEY_DARK_FROM, DEFAULT_DARK_FROM);
    }

    static int lightFrom(Context ctx) {
        return prefs(ctx).getInt(KEY_LIGHT_FROM, DEFAULT_LIGHT_FROM);
    }

    static void setDarkFrom(Context ctx, int minutes) {
        prefs(ctx).edit().putInt(KEY_DARK_FROM, wrap(minutes)).apply();
    }

    static void setLightFrom(Context ctx, int minutes) {
        prefs(ctx).edit().putInt(KEY_LIGHT_FROM, wrap(minutes)).apply();
    }

    static int wrap(int minutes) {
        int day = 24 * 60;
        return ((minutes % day) + day) % day;
    }

    /** The theme that should be showing right now. */
    static boolean isDark(Context ctx) {
        int mode = mode(ctx);
        if (mode == MODE_DARK) return true;
        if (mode == MODE_LIGHT) return false;
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return inDarkWindow(minute, darkFrom(ctx), lightFrom(ctx));
    }

    /**
     * Dark from darkFrom until lightFrom, across midnight when darkFrom is the later of
     * the two (the usual evening-to-morning case). Equal times mean never dark.
     */
    static boolean inDarkWindow(int minute, int darkFrom, int lightFrom) {
        if (darkFrom == lightFrom) return false;
        if (darkFrom > lightFrom) return minute >= darkFrom || minute < lightFrom;
        return minute >= darkFrom && minute < lightFrom;
    }

    static String formatMinutes(int minutes) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /**
     * The override an activity must apply in attachBaseContext, before calling super:
     * ContextThemeWrapper throws once its resources have been resolved.
     *
     * The night bits are always written, never left alone. The box has a system night
     * setting of its own, and inheriting it would mean this button only worked half the
     * time; writing NIGHT_NO as explicitly as NIGHT_YES makes our setting the only one
     * that counts.
     *
     * The type bits are carried over rather than zeroed. uiMode packs the device type and
     * the night flag into one field, so writing the night flag alone would tell the
     * resource system this is no longer a television.
     */
    static Configuration override(Context base, boolean dark) {
        Configuration cfg = new Configuration();
        int type = base.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        cfg.uiMode = type | (dark
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO);
        return cfg;
    }
}
