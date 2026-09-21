package local.kutu.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Kutu Home.
 *
 * Guide 15: plain platform Views, no AndroidX, no Compose, no GMS, no network
 * permission, no background service, no wakelock, no boot receiver, no database.
 * The clock is driven by ACTION_TIME_TICK rather than a polling loop, so the
 * launcher does no work at all while it sits idle.
 */
public final class HomeActivity extends Activity {

    private static final int MAX_UNSCROLLED_TILES = 7;   // guide 19
    private static final String ADD_TILE_TAG = "__add__";

    private ViewGroup dockRow;
    private HorizontalScrollView shelf;
    private LinearLayout chipsRow;
    private TextView focusedLabel;
    private TextView moveHint;
    private TextView clockTime;
    private TextView clockDate;

    private final List<String[]> dock = new ArrayList<>();
    private List<String[]> dockBeforeMove;
    private int moveIndex = -1;
    /** Key whose ACTION_UP must be discarded because its ACTION_DOWN ended move mode. */
    private int swallowUpKeyCode = -1;

    /**
     * What had focus when the launcher was last left. onResume rebuilds the dock, and
     * without this every return from a panel or an app dropped focus onto the first
     * tile instead of the chip or tile the person came from.
     */
    private String lastFocusedPkg;
    private int lastFocusedChip = -1;

    /** renderDock sentinel: lay the shelf out but leave focus alone, the caller places it */
    private static final String KEEP_FOCUS = "\u0000keep-focus";

    private SimpleDateFormat timeFormat;
    private SimpleDateFormat dateFormat;
    private KutuMenu openMenu;

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            IconNormalizer.clearCache();
            rebuildDock();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_home);

        dockRow = findViewById(R.id.dock_row);
        shelf = findViewById(R.id.shelf);
        chipsRow = findViewById(R.id.chips_row);
        focusedLabel = findViewById(R.id.focused_label);
        moveHint = findViewById(R.id.move_hint);
        clockTime = findViewById(R.id.clock_time);
        clockDate = findViewById(R.id.clock_date);

        Locale tr = new Locale("tr", "TR");
        timeFormat = new SimpleDateFormat("HH:mm", tr);
        dateFormat = new SimpleDateFormat("d MMMM EEEE", tr);

        // guide 19: never draw an icon outside the rounded shelf
        shelf.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        shelf.setClipToOutline(true);

        buildChips();
        updateClock();
    }

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter clockFilter = new IntentFilter(Intent.ACTION_TIME_TICK);
        clockFilter.addAction(Intent.ACTION_TIME_CHANGED);
        clockFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockReceiver, clockFilter);

        IntentFilter pkgFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);
    }

    @Override
    protected void onStop() {
        super.onStop();
        safeUnregister(clockReceiver);
        safeUnregister(packageReceiver);
    }

    private void safeUnregister(BroadcastReceiver r) {
        try {
            unregisterReceiver(r);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        rebuildDock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // guide 22: leaving before confirm cancels the move safely
        if (moveIndex >= 0) cancelMove();
        if (openMenu != null && openMenu.isShowing()) {
            openMenu.dismiss();
            openMenu = null;
        }
    }

    /**
     * Guide 30: HOME must always land on a clean Kutu Home. Pressing HOME while
     * already here closes any menu, cancels an unconfirmed move and returns focus
     * to the shelf.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openMenu != null && openMenu.isShowing()) {
            openMenu.dismiss();
            openMenu = null;
        }
        if (moveIndex >= 0) cancelMove();
        shelf.scrollTo(0, 0);
        rememberFocus(null, -1);
        focusFirstTile();
    }

    /** BACK on the home screen must not leave the launcher. */
    @Override
    public void onBackPressed() {
        if (moveIndex >= 0) {
            cancelMove();
            return;
        }
        // stay put: a launcher is the bottom of the stack
    }

    private void updateClock() {
        Date now = new Date();
        clockTime.setText(timeFormat.format(now));
        String date = dateFormat.format(now);
        if (!date.isEmpty()) date = date.substring(0, 1).toUpperCase(new Locale("tr", "TR")) + date.substring(1);
        clockDate.setText(date);
    }

    // ---------------------------------------------------------------- dock

    private void rebuildDock() {
        dock.clear();
        dock.addAll(DockStore.load(this));
        if (lastFocusedChip >= 0) {
            // returning from a chip panel: the shelf is rebuilt, but focus belongs on the chip
            renderDock(KEEP_FOCUS);
            focusChip(lastFocusedChip);
        } else {
            renderDock(lastFocusedPkg);
        }
    }

    private void renderDock(String focusPkg) {
        dockRow.removeAllViews();

        if (dock.isEmpty()) {
            dockRow.addView(buildAddTile());
            clampShelfWidth();
            applyFocus(focusPkg);
            return;
        }

        int iconPx = getResources().getDimensionPixelSize(R.dimen.tile_icon);
        LayoutInflater inflater = LayoutInflater.from(this);
        int gap = getResources().getDimensionPixelSize(R.dimen.tile_gap);

        for (int i = 0; i < dock.size(); i++) {
            String[] row = dock.get(i);
            final AppEntry entry = AppRepository.lookup(this, row[0], row.length > 1 ? row[1] : "");

            // remember the freshest label we have seen for this favourite
            if (entry.available && row.length > 1 && !entry.label.equals(row[1])) {
                dock.set(i, new String[]{entry.pkg, entry.label});
                DockStore.save(this, dock);
            }

            View tile = inflater.inflate(R.layout.view_tile, dockRow, false);
            ImageView icon = tile.findViewById(R.id.tile_icon);

            if (entry.available && entry.icon != null) {
                icon.setImageDrawable(IconNormalizer.normalize(this, entry.pkg, entry.icon, iconPx));
                tile.setAlpha(1f);
            } else {
                // guide 19: dim an unavailable tile, never crash
                icon.setImageDrawable(getDrawable(R.drawable.kutu_icon));
                tile.setAlpha(0.38f);
            }

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            lp.setMarginStart(i == 0 ? 0 : gap);
            tile.setLayoutParams(lp);
            tile.setTag(entry.pkg);

            TileBehaviour.attach(tile, new TileBehaviour.Callbacks() {
                @Override
                public void onShortPress(View v) {
                    launch(entry);
                }

                @Override
                public void onLongPress(View v) {
                    showDockMenu(entry);
                }

                @Override
                public void onFocusChanged(View v, boolean focused) {
                    if (focused) {
                        rememberFocus(entry.pkg, -1);
                        focusedLabel.setText(entry.available
                                ? entry.label
                                : entry.label + " · " + getString(R.string.app_unavailable));
                    }
                }
            });

            dockRow.addView(tile);
        }

        // No permanent "+" tile: adding is done by long-pressing an app in All Apps,
        // which the human finds sufficient. Guide 19's "+" survives only for the
        // zero-favourites case, handled at the top of this method.

        clampShelfWidth();
        applyFocus(focusPkg);
    }

    /**
     * Places focus after a re-render: on the requested tile when it still exists,
     * otherwise on the first one. KEEP_FOCUS means the caller places it instead.
     */
    private void applyFocus(String focusPkg) {
        if (KEEP_FOCUS.equals(focusPkg)) return;
        if (focusPkg != null) {
            View t = dockRow.findViewWithTag(focusPkg);
            if (t != null) {
                final View target = t;
                target.post(new Runnable() {
                    @Override
                    public void run() {
                        target.requestFocus();
                    }
                });
                return;
            }
        }
        focusFirstTile();
    }

    private void rememberFocus(String pkg, int chipIndex) {
        lastFocusedPkg = pkg;
        lastFocusedChip = chipIndex;
    }

    private void focusChip(final int index) {
        if (index < 0 || index >= chipsRow.getChildCount()) {
            focusFirstTile();
            return;
        }
        final View chip = chipsRow.getChildAt(index);
        chip.post(new Runnable() {
            @Override
            public void run() {
                chip.requestFocus();
            }
        });
    }

    /** Guide 19: at most ~7 visible slots; beyond that the shelf scrolls instead of growing. */
    private void clampShelfWidth() {
        shelf.post(new Runnable() {
            @Override
            public void run() {
                int overscan = getResources().getDimensionPixelSize(R.dimen.overscan_h);
                int max = getResources().getDisplayMetrics().widthPixels - (overscan * 2);

                int tile = getResources().getDimensionPixelSize(R.dimen.tile_outer);
                int gap = getResources().getDimensionPixelSize(R.dimen.tile_gap);
                int shelfPad = getResources().getDimensionPixelSize(R.dimen.shelf_padding) * 2;
                int rowPad = getResources().getDimensionPixelSize(R.dimen.row_padding) * 2;
                int slots = MAX_UNSCROLLED_TILES;
                int widest = (tile * slots) + (gap * (slots - 1)) + rowPad + shelfPad;

                int cap = Math.min(max, widest);
                ViewGroup.LayoutParams lp = shelf.getLayoutParams();
                // dockRow.getWidth() already includes its own padding
                int natural = dockRow.getWidth() + shelfPad;

                // fewer than seven: shelf shrinks and stays centred
                lp.width = natural <= cap ? ViewGroup.LayoutParams.WRAP_CONTENT : cap;
                shelf.setLayoutParams(lp);
            }
        });
    }

    private View buildAddTile() {
        View tile = LayoutInflater.from(this).inflate(R.layout.view_tile, dockRow, false);
        ImageView icon = tile.findViewById(R.id.tile_icon);
        icon.setImageDrawable(getDrawable(R.drawable.ic_add));
        tile.setTag(ADD_TILE_TAG);
        TileBehaviour.attach(tile, new TileBehaviour.Callbacks() {
            @Override
            public void onShortPress(View v) {
                openAllApps();
            }

            @Override
            public void onLongPress(View v) {
                openAllApps();
            }

            @Override
            public void onFocusChanged(View v, boolean focused) {
                if (focused) {
                    rememberFocus(ADD_TILE_TAG, -1);
                    focusedLabel.setText(dock.isEmpty() ? R.string.empty_dock : R.string.tile_add);
                }
            }
        });
        return tile;
    }

    private void focusFirstTile() {
        if (dockRow.getChildCount() == 0) return;
        final View first = dockRow.getChildAt(0);
        first.post(new Runnable() {
            @Override
            public void run() {
                first.requestFocus();
            }
        });
    }

    private void launch(AppEntry entry) {
        if (!entry.available) {
            Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = AppRepository.launchIntent(this, entry.pkg);
        if (i == null) {
            Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------- menus

    /** Guide 21: menu for an app that is already on the home shelf. */
    private void showDockMenu(final AppEntry entry) {
        KutuMenu.Builder b = KutuMenu.on(this, entry.label)
                .add(getString(R.string.menu_move), new Runnable() {
                    @Override
                    public void run() {
                        beginMove(entry.pkg);
                    }
                })
                .add(getString(R.string.menu_remove), new Runnable() {
                    @Override
                    public void run() {
                        int idx = DockStore.indexOf(dock, entry.pkg);
                        DockStore.remove(dock, entry.pkg);
                        DockStore.save(HomeActivity.this, dock);
                        // focus what slid into the gap, or the new last tile when the
                        // end of the shelf was the one removed
                        String neighbour = null;
                        if (!dock.isEmpty() && idx >= 0) {
                            neighbour = dock.get(Math.min(idx, dock.size() - 1))[0];
                        }
                        rememberFocus(neighbour, -1);
                        renderDock(neighbour);
                    }
                })
                .addIf(entry.available, getString(R.string.menu_app_info), new Runnable() {
                    @Override
                    public void run() {
                        safeStart(AppRepository.appInfoIntent(entry.pkg));
                    }
                })
                .addIf(entry.available && entry.uninstallable, getString(R.string.menu_uninstall), new Runnable() {
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

    // -------------------------------------------------------------- move

    /** Guide 22: the selected tile lifts, LEFT/RIGHT moves, OK saves, BACK cancels. */
    private void beginMove(String pkg) {
        int idx = DockStore.indexOf(dock, pkg);
        if (idx < 0) return;

        dockBeforeMove = new ArrayList<>();
        for (String[] row : dock) dockBeforeMove.add(new String[]{row[0], row.length > 1 ? row[1] : ""});

        moveIndex = idx;
        moveHint.setVisibility(View.VISIBLE);
        renderDock(pkg);
        markMoveTile(true);
    }

    private void markMoveTile(boolean lifted) {
        if (moveIndex < 0 || moveIndex >= dockRow.getChildCount()) return;
        View tile = dockRow.getChildAt(moveIndex);
        tile.setTranslationY(lifted ? -14f * getResources().getDisplayMetrics().density : 0f);
    }

    /** Package currently in move mode, so focus can stay on it once the move ends. */
    private String movingPkg() {
        if (moveIndex < 0 || moveIndex >= dock.size()) return null;
        return dock.get(moveIndex)[0];
    }

    private void commitMove() {
        // Focus must land back on the tile that was moved, not on the first one.
        String moved = movingPkg();
        moveIndex = -1;
        dockBeforeMove = null;
        moveHint.setVisibility(View.GONE);
        DockStore.save(this, dock);
        renderDock(moved);
    }

    private void cancelMove() {
        String moved = movingPkg();
        if (dockBeforeMove != null) {
            dock.clear();
            dock.addAll(dockBeforeMove);
        }
        moveIndex = -1;
        dockBeforeMove = null;
        moveHint.setVisibility(View.GONE);
        renderDock(moved);
    }

    private void shiftMove(int delta) {
        int target = moveIndex + delta;
        if (target < 0 || target >= dock.size()) return;
        String[] row = dock.remove(moveIndex);
        dock.add(target, row);
        moveIndex = target;
        renderDock(row[0]);
        markMoveTile(true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();

        // The key that ended move mode must have its ACTION_UP swallowed here, before
        // anything else looks at moveIndex. Committing clears moveIndex on ACTION_DOWN,
        // so a moveIndex-guarded check would already be false by the time UP arrives and
        // the event would reach the focused tile and launch it.
        if (event.getAction() == KeyEvent.ACTION_UP && code == swallowUpKeyCode) {
            swallowUpKeyCode = -1;
            return true;
        }

        if (moveIndex >= 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    shiftMove(-1);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    shiftMove(1);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_BACK) {
                    swallowUpKeyCode = code;
                    cancelMove();
                    return true;
                }
                if (TileBehaviour.isSelectKey(code)) {
                    swallowUpKeyCode = code;
                    commitMove();
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP
                    && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------- chips

    private void buildChips() {
        chipsRow.removeAllViews();
        addChip(getString(R.string.chip_mirror), new Runnable() {
            @Override
            public void run() {
                // guide 23: never open MirrorActivity; open our own panel
                startActivity(new Intent(HomeActivity.this, MirrorPanelActivity.class));
            }
        });
        addChip(getString(R.string.chip_settings), new Runnable() {
            @Override
            public void run() {
                openSettings();
            }
        });
        addChip(getString(R.string.chip_all_apps), new Runnable() {
            @Override
            public void run() {
                openAllApps();
            }
        });
    }

    private void addChip(String label, final Runnable action) {
        TextView chip = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.view_chip, chipsRow, false);
        chip.setText(label);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (chipsRow.getChildCount() > 0) {
            lp.setMarginStart(getResources().getDimensionPixelSize(R.dimen.chip_gap));
        }
        chip.setLayoutParams(lp);

        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });

        final int index = chipsRow.getChildCount();
        chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                if (focused) rememberFocus(null, index);
            }
        });
        chipsRow.addView(chip);
    }

    private void openAllApps() {
        startActivity(new Intent(this, AllAppsActivity.class));
    }

    /**
     * Guide 23: standard Settings action first; only fall back to a firmware-specific
     * target when the standard one does not resolve on this device.
     */
    private void openSettings() {
        Intent std = new Intent(Settings.ACTION_SETTINGS);
        std.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (std.resolveActivity(getPackageManager()) != null) {
            safeStart(std);
            return;
        }
        Intent leanback = new Intent("android.settings.SETTINGS");
        leanback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (leanback.resolveActivity(getPackageManager()) != null) {
            safeStart(leanback);
            return;
        }
        Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show();
    }
}
