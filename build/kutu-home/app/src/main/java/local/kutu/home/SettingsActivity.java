package local.kutu.home;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Kutu Home's own settings, opened from the gear at the top left.
 *
 * One light-glass card of rows. A row with a value changes with LEFT/RIGHT (and OK steps
 * it forward); a row that leads somewhere opens on OK. BACK closes and focus returns to
 * the gear. Everything is stored in the launcher's "home" preferences; nothing here
 * touches the system.
 */
public final class SettingsActivity extends Activity {

    private static final String UPDATE_ACTIVITY = "local.kutu.transfer.UpdateActivity";
    private static final int REQ_LOCATION = 42;

    /** survives the recreate() a theme change triggers, so focus stays on the row that did it */
    private static int focusRowAfterRecreate = -1;

    private LinearLayout rows;
    private boolean shownDark;

    private Row theme;
    private Row darkFrom;
    private Row lightFrom;
    private Row background;
    private Row visible;
    private Row wifiName;

    @Override
    protected void attachBaseContext(Context base) {
        applyOverrideConfiguration(ThemeStore.override(base, ThemeStore.isDark(base)));
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_settings);
        shownDark = ThemeStore.isDark(this);
        rows = findViewById(R.id.settings_rows);

        theme = addRow(R.string.set_theme, new Adjust() {
            @Override
            public void step(int dir) {
                int mode = ThemeStore.mode(SettingsActivity.this);
                ThemeStore.setMode(SettingsActivity.this, ((mode + dir) % 3 + 3) % 3);
                afterThemeChange(0);
            }
        }, null);
        darkFrom = addRow(R.string.set_dark_from, new Adjust() {
            @Override
            public void step(int dir) {
                ThemeStore.setDarkFrom(SettingsActivity.this,
                        ThemeStore.darkFrom(SettingsActivity.this) + dir * ThemeStore.STEP_MIN);
                afterThemeChange(1);
            }
        }, null);
        lightFrom = addRow(R.string.set_light_from, new Adjust() {
            @Override
            public void step(int dir) {
                ThemeStore.setLightFrom(SettingsActivity.this,
                        ThemeStore.lightFrom(SettingsActivity.this) + dir * ThemeStore.STEP_MIN);
                afterThemeChange(2);
            }
        }, null);
        background = addRow(R.string.set_background, null, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, BackgroundActivity.class));
            }
        });
        visible = addRow(R.string.set_visible, new Adjust() {
            @Override
            public void step(int dir) {
                int n = HomeSettings.visibleTiles(SettingsActivity.this) + dir;
                if (n > HomeSettings.VISIBLE_MAX) n = HomeSettings.VISIBLE_MIN;
                if (n < HomeSettings.VISIBLE_MIN) n = HomeSettings.VISIBLE_MAX;
                HomeSettings.setVisibleTiles(SettingsActivity.this, n);
                refresh();
            }
        }, null);
        wifiName = addRow(R.string.set_wifi_name, null, new Runnable() {
            @Override
            public void run() {
                askForWifiName();
            }
        });
        addRow(R.string.set_update, null, new Runnable() {
            @Override
            public void run() {
                openUpdater();
            }
        });
        Row version = addRow(R.string.set_version, null, null);
        version.view.setFocusable(false);
        version.value.setText(versions());

        refresh();

        int focus = focusRowAfterRecreate >= 0 ? focusRowAfterRecreate : 0;
        focusRowAfterRecreate = -1;
        final View target = rows.getChildAt(focus);
        target.post(new Runnable() {
            @Override
            public void run() {
                target.requestFocus();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();   // the background may have changed in the picker
    }

    private void refresh() {
        int mode = ThemeStore.mode(this);
        theme.value.setText(mode == ThemeStore.MODE_DARK ? R.string.set_theme_dark
                : mode == ThemeStore.MODE_AUTO ? R.string.set_theme_auto
                : R.string.set_theme_light);

        boolean auto = mode == ThemeStore.MODE_AUTO;
        darkFrom.value.setText(ThemeStore.formatMinutes(ThemeStore.darkFrom(this)));
        lightFrom.value.setText(ThemeStore.formatMinutes(ThemeStore.lightFrom(this)));
        // the times only matter in automatic mode; out of it they are shown but skipped
        for (Row r : new Row[]{darkFrom, lightFrom}) {
            r.view.setFocusable(auto);
            r.view.setAlpha(auto ? 1f : 0.4f);
        }

        background.value.setText(HomeSettings.hasCustomBackground(this)
                ? R.string.set_background_custom : R.string.set_background_default);
        visible.value.setText(String.valueOf(HomeSettings.visibleTiles(this)));
        wifiName.value.setText(HomeSettings.canReadWifiName(this)
                ? R.string.set_wifi_name_on : R.string.set_wifi_name_off);
    }

    /**
     * Asks for location access, which is all Android 9 wants before it shows the Wi-Fi
     * network's name. If the human once chose "don't ask again", Android refuses silently,
     * so the app's own settings page is opened instead, where it can be switched on.
     */
    private void askForWifiName() {
        if (HomeSettings.canReadWifiName(this)) return;
        String perm = android.Manifest.permission.ACCESS_COARSE_LOCATION;
        if (HomeSettings.askedLocation(this) && !shouldShowRequestPermissionRationale(perm)) {
            try {
                startActivity(AppRepository.appInfoIntent(getPackageName()));
                return;
            } catch (Exception ignored) {
            }
        }
        HomeSettings.setAskedLocation(this);
        requestPermissions(new String[]{perm}, REQ_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }

    /** A theme setting changed; redraw in the other theme when that is now due. */
    private void afterThemeChange(int rowIndex) {
        if (ThemeStore.isDark(this) != shownDark) {
            focusRowAfterRecreate = rowIndex;
            recreate();
            return;
        }
        refresh();
    }

    private void openUpdater() {
        Intent i = new Intent();
        i.setComponent(new ComponentName(AppRepository.TRANSFER_PKG, UPDATE_ACTIVITY));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.update_missing, Toast.LENGTH_LONG).show();
        }
    }

    private String versions() {
        StringBuilder sb = new StringBuilder();
        String[][] apps = {
                {getPackageName(), "Home"},
                {AppRepository.MIRROR_PKG, "Yansıtma"},
                {AppRepository.TRANSFER_PKG, "Aktarım"},
        };
        for (String[] app : apps) {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(app[0], 0);
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(app[1]).append(' ').append(pi.versionName);
            } catch (Exception ignored) {
                // not installed: simply not listed
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ rows

    private interface Adjust {
        /** @param dir +1 for RIGHT/OK, -1 for LEFT */
        void step(int dir);
    }

    private static final class Row {
        final View view;
        final TextView value;

        Row(View view, TextView value) {
            this.view = view;
            this.value = value;
        }
    }

    private Row addRow(int labelRes, final Adjust adjust, final Runnable open) {
        View v = LayoutInflater.from(this).inflate(R.layout.view_settings_row, rows, false);
        ((TextView) v.findViewById(R.id.row_label)).setText(labelRes);
        TextView value = v.findViewById(R.id.row_value);

        // LEFT/RIGHT act on key-down, so holding them repeats. OK acts on key-UP, like a tile
        // launch (guide 20): acting on DOWN opened the next screen while the key was still
        // held, and its UP then landed on whatever that screen had focused.
        v.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                if (adjust != null && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    if (down) adjust.step(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                }
                if (TileBehaviour.isSelectKey(keyCode) && (adjust != null || open != null)) {
                    if (!down && event.getRepeatCount() == 0 && !event.isCanceled()) {
                        if (adjust != null) adjust.step(1);
                        else open.run();
                    }
                    return true;
                }
                return false;
            }
        });
        rows.addView(v);
        return new Row(v, value);
    }
}
