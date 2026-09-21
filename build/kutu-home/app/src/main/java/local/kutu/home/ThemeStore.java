package local.kutu.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

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
 * Shares DockStore's preferences file; this is the same "home" store, one more key.
 */
final class ThemeStore {

    private static final String PREFS = "home";
    private static final String KEY = "theme_dark_v1";

    private ThemeStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Light is the default: it is the design the launcher shipped with. */
    static boolean isDark(Context ctx) {
        return prefs(ctx).getBoolean(KEY, false);
    }

    static void setDark(Context ctx, boolean dark) {
        prefs(ctx).edit().putBoolean(KEY, dark).apply();
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
