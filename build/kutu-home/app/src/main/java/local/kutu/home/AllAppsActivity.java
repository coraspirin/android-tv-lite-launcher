package local.kutu.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Guide 23: All Apps. Lists user-facing launchable TV apps, excluding Kutu Home,
 * Kutu Mirror and Kutu Aktarım, using the same normalised icon system as the shelf.
 * Title and focused label are centred; apps already on the shelf carry a white dot.
 */
public final class AllAppsActivity extends Activity {

    /** Follows the launcher's own light/dark setting; see ThemeStore. */
    @Override
    protected void attachBaseContext(Context base) {
        applyOverrideConfiguration(ThemeStore.override(base, ThemeStore.isDark(base)));
        super.attachBaseContext(base);
    }

    private GridLayout grid;
    private ScrollView scroll;
    private TextView focusedLabel;
    private final List<String[]> dock = new ArrayList<>();
    private KutuMenu openMenu;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_all_apps);
        grid = findViewById(R.id.all_apps_grid);
        scroll = findViewById(R.id.all_apps_scroll);
        focusedLabel = findViewById(R.id.all_apps_focused_label);
        HomeSettings.applyBackground(this, (ImageView) findViewById(R.id.background));
    }

    @Override
    protected void onResume() {
        super.onResume();
        dock.clear();
        dock.addAll(DockStore.load(this));
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (openMenu != null && openMenu.isShowing()) {
            openMenu.dismiss();
            openMenu = null;
        }
    }

    private void render() {
        grid.removeAllViews();
        grid.setColumnCount(columns());

        List<AppEntry> apps = AppRepository.loadLaunchable(this);
        int iconPx = getResources().getDimensionPixelSize(R.dimen.allapps_icon);
        int cell = getResources().getDimensionPixelSize(R.dimen.allapps_outer);
        int gap = getResources().getDimensionPixelSize(R.dimen.tile_gap);
        LayoutInflater inflater = LayoutInflater.from(this);

        View first = null;
        for (final AppEntry entry : apps) {
            View tile = inflater.inflate(R.layout.view_allapps_tile, grid, false);
            ImageView icon = tile.findViewById(R.id.tile_icon);
            if (entry.icon != null) {
                Drawable art = IconNormalizer.normalize(this, entry.pkg, entry.icon, iconPx);
                icon.setImageDrawable(art);
                TileGlass.apply(tile.findViewById(R.id.tile_card), entry.pkg, art);
            } else {
                icon.setImageDrawable(getDrawable(R.drawable.kutu_icon));
            }

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = cell;
            lp.setMargins(0, 0, 0, 0);
            tile.setLayoutParams(lp);
            tile.setTag(entry.pkg);
            markOnHome(tile, entry.pkg);

            TileBehaviour.attach(tile, new TileBehaviour.Callbacks() {
                @Override
                public void onShortPress(View v) {
                    Intent i = AppRepository.launchIntent(AllAppsActivity.this, entry.pkg);
                    if (i == null) {
                        Toast.makeText(AllAppsActivity.this, R.string.app_unavailable, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(AllAppsActivity.this, R.string.app_unavailable, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onLongPress(View v) {
                    showMenu(entry);
                }

                @Override
                public void onFocusChanged(View v, boolean focused) {
                    if (focused) {
                        showLabel(entry);
                        ensureVisible(v);
                    }
                }
            });

            grid.addView(tile);
            if (first == null) first = tile;
        }

        if (first != null) {
            final View target = first;
            target.post(new Runnable() {
                @Override
                public void run() {
                    target.requestFocus();
                }
            });
        }
    }

    /**
     * As many columns as the screen holds. A fixed count left a strip of unused width at
     * the right of every row on a 1080p box; the grid itself is centred in the layout, so
     * whatever is left over after the last whole column is split evenly on both sides.
     */
    private int columns() {
        int overscan = getResources().getDimensionPixelSize(R.dimen.overscan_h);
        int scrollPad = scroll.getPaddingLeft() + scroll.getPaddingRight();
        int avail = getResources().getDisplayMetrics().widthPixels - (overscan * 2) - scrollPad;
        int cell = getResources().getDimensionPixelSize(R.dimen.allapps_outer);
        return Math.max(1, avail / cell);
    }

    /** The white dot on an app that is already on the home shelf. */
    private void markOnHome(View tile, String pkg) {
        if (tile == null) return;
        View dot = tile.findViewById(R.id.on_home_dot);
        if (dot != null) dot.setVisibility(DockStore.contains(dock, pkg) ? View.VISIBLE : View.GONE);
    }

    private void ensureVisible(final View v) {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int top = v.getTop() + ((ViewGroup) v.getParent()).getTop();
                int bottom = top + v.getHeight();
                int viewTop = scroll.getScrollY();
                int viewBottom = viewTop + scroll.getHeight();
                if (top < viewTop) {
                    scroll.smoothScrollTo(0, Math.max(0, top - v.getHeight() / 2));
                } else if (bottom > viewBottom) {
                    scroll.smoothScrollTo(0, bottom - scroll.getHeight() + v.getHeight() / 2);
                }
            }
        });
    }

    /**
     * Shows the focused app's name, and says when it is already on the home shelf.
     * Without this the menu looks inconsistent: an app already on the shelf offers
     * "remove" while another offers "add", with nothing on screen explaining why.
     */
    private void showLabel(AppEntry entry) {
        focusedLabel.setText(DockStore.contains(dock, entry.pkg)
                ? entry.label + "  ·  " + getString(R.string.on_home_suffix)
                : entry.label);
    }

    /** Guide 21: the All Apps variant of the context menu. */
    private void showMenu(final AppEntry entry) {
        final boolean onHome = DockStore.contains(dock, entry.pkg);
        KutuMenu.Builder b = KutuMenu.on(this, entry.label);

        if (onHome) {
            b.add(getString(R.string.menu_remove), new Runnable() {
                @Override
                public void run() {
                    DockStore.remove(dock, entry.pkg);
                    DockStore.save(AllAppsActivity.this, dock);
                    showLabel(entry);
                    markOnHome(grid.findViewWithTag(entry.pkg), entry.pkg);
                }
            });
        } else {
            b.add(getString(R.string.menu_add), new Runnable() {
                @Override
                public void run() {
                    dock.add(new String[]{entry.pkg, entry.label});
                    DockStore.save(AllAppsActivity.this, dock);
                    showLabel(entry);
                    markOnHome(grid.findViewWithTag(entry.pkg), entry.pkg);
                }
            });
        }

        b.add(getString(R.string.menu_app_info), new Runnable() {
            @Override
            public void run() {
                safeStart(AppRepository.appInfoIntent(entry.pkg));
            }
        });
        b.addIf(entry.uninstallable, getString(R.string.menu_uninstall), new Runnable() {
            @Override
            public void run() {
                safeStart(AppRepository.uninstallIntent(entry.pkg));
            }
        });

        openMenu = b.show();
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show();
        }
    }
}
