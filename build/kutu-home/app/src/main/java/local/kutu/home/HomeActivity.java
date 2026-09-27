package local.kutu.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
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
 * Guide 15: plain platform Views, no AndroidX, no Compose, no GMS, no INTERNET
 * permission, no background service, no wakelock, no boot receiver, no database.
 * The clock is driven by ACTION_TIME_TICK and the Wi-Fi line by a default-network
 * callback rather than a polling loop, so the launcher does no work at all while it
 * sits idle.
 */
public final class HomeActivity extends Activity {

    private static final String ADD_TILE_TAG = "__add__";

    /**
     * Chips lift less than tiles. They are far wider than they are tall, so a tile's
     * 114% would read as a lurch rather than a hover, and a chip sits outside the
     * shelf's clip box where a big shadow has nothing to sit against.
     */
    private static final float CHIP_FOCUS_SCALE = 1.06f;
    private static final float CHIP_FOCUS_LIFT_DP = 4f;
    /** how far the tile being moved rises above the row (guide 22) */
    private static final float MOVE_LIFT_DP = 8f;
    /** the settings button is small, so it grows more on focus to show where focus is */
    private static final float SETTINGS_FOCUS_SCALE = 1.35f;

    /** the entry animation: short, and only a small slide so a weak GPU is not taxed */
    private static final long ENTRY_ANIM_MS = 160L;
    private static final float ENTRY_SLIDE_DP = 12f;

    private ViewGroup dockRow;
    private HorizontalScrollView shelf;
    private LinearLayout chipsRow;
    private LinearLayout pageDots;
    private TextView focusedLabel;
    private TextView moveHint;
    private TextView clockTime;
    private TextView clockDate;
    private View settingsButton;
    private ImageView background;
    private ImageView netIcon;
    private TextView netLabel;
    private ImageView batteryIcon;
    private TextView batteryLabel;

    private NetworkStatus networkStatus;

    /** the theme this activity was created with; a different isDark() means recreate */
    private boolean shownDark;

    private final List<String[]> dock = new ArrayList<>();
    private List<String[]> dockBeforeMove;
    private int moveIndex = -1;
    /** Key whose ACTION_UP must be discarded because its ACTION_DOWN ended move mode. */
    private int swallowUpKeyCode = -1;

    /** how many tiles the shelf shows before it scrolls, and the tile scale that fits them */
    private int visibleTiles = HomeSettings.VISIBLE_DEFAULT;
    private float tileScale = 1f;
    private Locale uiLocale = Locale.getDefault();

    /**
     * What had focus when the launcher was last left. onResume rebuilds the dock, and
     * without this every return from a panel or an app dropped focus onto the first
     * tile instead of the chip, tile or top button the person came from.
     */
    private String lastFocusedPkg;
    private int lastFocusedChip = -1;
    private int lastFocusedTop;

    /**
     * Set when Kutu Home opens one of its own screens (All Apps, the panels, settings).
     * Coming back from those is not "arriving home", so it does not replay the entry
     * animation; coming back from an app, or pressing HOME, does.
     */
    private boolean openingOwnScreen;
    private boolean pendingEntryAnimation;

    /** modification stamp of the background file currently shown */
    private long backgroundStamp = Long.MIN_VALUE;

    private static final int REQ_LOCATION = 41;
    /** let the home screen settle first: a dialog raised while it restarts closes at once */
    private static final long ASK_DELAY_MS = 1500L;

    private boolean resumed;

    /** renderDock sentinel: lay the shelf out but leave focus alone, the caller places it */
    private static final String KEEP_FOCUS = "\u0000keep-focus";

    private SimpleDateFormat timeFormat;
    private SimpleDateFormat dateFormat;
    private KutuMenu openMenu;

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
            // automatic theme: the same minute tick that moves the clock flips the theme
            if (ThemeStore.isDark(HomeActivity.this) != shownDark) recreate();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            IconNormalizer.clearCache();
            // the card colours are derived from those icons, so they go stale with them
            TileGlass.clearCache();
            // an uninstalled favourite leaves the shelf; rebuildDock prunes it. An update
            // arrives as REMOVED with EXTRA_REPLACING and must not touch the shelf.
            rebuildDock();
        }
    };

    /** ContextThemeWrapper throws once resources exist, so this runs before super. */
    @Override
    protected void attachBaseContext(Context base) {
        applyOverrideConfiguration(ThemeStore.override(base, ThemeStore.isDark(base)));
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_home);
        shownDark = ThemeStore.isDark(this);

        dockRow = findViewById(R.id.dock_row);
        shelf = findViewById(R.id.shelf);
        chipsRow = findViewById(R.id.chips_row);
        pageDots = findViewById(R.id.page_dots);
        focusedLabel = findViewById(R.id.focused_label);
        moveHint = findViewById(R.id.move_hint);
        clockTime = findViewById(R.id.clock_time);
        clockDate = findViewById(R.id.clock_date);
        settingsButton = findViewById(R.id.settings_button);
        background = findViewById(R.id.background);
        netIcon = findViewById(R.id.net_icon);
        netLabel = findViewById(R.id.net_label);
        batteryIcon = findViewById(R.id.battery_icon);
        batteryLabel = findViewById(R.id.battery_label);

        // the system language picks the words and the order: "27 Eylül Pazar" / "Sunday, 27 September"
        uiLocale = getResources().getConfiguration().getLocales().get(0);
        timeFormat = new SimpleDateFormat("HH:mm", uiLocale);
        dateFormat = new SimpleDateFormat(getString(R.string.date_pattern), uiLocale);

        // guide 19: never draw an icon outside the rounded shelf
        shelf.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        shelf.setClipToOutline(true);
        shelf.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                if (x != oldX) updateActiveDot();
            }
        });

        networkStatus = new NetworkStatus(this, new NetworkStatus.Listener() {
            @Override
            public void onNetworkStatus(int kind, String name) {
                showNetwork(kind, name);
            }
        });

        buildChips();
        buildTopButtons();
        updateClock();
    }

    // ------------------------------------------------------- top-left button

    /**
     * The gear opens Kutu Home's own settings, where the theme is chosen too. The home
     * screen itself carries no theme button any more.
     */
    private void buildTopButtons() {
        attachGlassFocus(settingsButton, SETTINGS_FOCUS_SCALE, new Runnable() {
            @Override
            public void run() {
                rememberTop(R.id.settings_button);
                focusedLabel.setText(R.string.kutu_settings);
            }
        });
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOwn(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
    }

    /**
     * The focus feel shared by the chips and the settings button. Both are glass outside the
     * shelf, where the state selector alone leaves them looking dead next to a tile that
     * scales and lifts. TileBehaviour's timing, so the whole screen moves alike (guide 18).
     */
    private void attachGlassFocus(final View v, final float scale, final Runnable onFocused) {
        final float lift = CHIP_FOCUS_LIFT_DP * getResources().getDisplayMetrics().density;
        final int glow = getColor(R.color.accent_glow);
        v.setOutlineSpotShadowColor(glow);
        v.setOutlineAmbientShadowColor(glow);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                float s = focused ? scale : 1f;
                view.animate()
                        .scaleX(s).scaleY(s)
                        .translationZ(focused ? lift : 0f)
                        .setDuration(TileBehaviour.ANIM_MS)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                if (focused && onFocused != null) onFocused.run();
            }
        });
    }

    // ------------------------------------------------------------- lifecycle

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

        networkStatus.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        safeUnregister(clockReceiver);
        safeUnregister(packageReceiver);
        networkStatus.stop();
    }

    private void safeUnregister(BroadcastReceiver r) {
        try {
            unregisterReceiver(r);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (!openingOwnScreen) pendingEntryAnimation = true;
        openingOwnScreen = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the automatic theme may have turned while an app was in front, or the mode was
        // changed in settings
        if (ThemeStore.isDark(this) != shownDark) {
            recreate();
            return;
        }
        applyBackgroundIfChanged();

        visibleTiles = HomeSettings.visibleTiles(this);

        updateClock();
        rebuildDock();
        RemoteBattery.refreshIfDue(this, new RemoteBattery.Listener() {
            @Override
            public void onRemoteBattery(int pct) {
                showBattery(pct);
            }
        });

        if (pendingEntryAnimation) {
            pendingEntryAnimation = false;
            playEntryAnimation();
        }
        resumed = true;
        askForWifiNameOnce();
    }

    /**
     * The Wi-Fi line can show the network's name only with location access (Android 9).
     * An update from GitHub cannot grant that, so the launcher asks once, on the box, the
     * first time it runs; later the settings screen's "Wi-Fi adı" row asks again on request.
     */
    private void askForWifiNameOnce() {
        if (HomeSettings.canReadWifiName(this) || HomeSettings.askedLocation(this)) return;
        settingsButton.removeCallbacks(askLocation);
        settingsButton.postDelayed(askLocation, ASK_DELAY_MS);
    }

    private final Runnable askLocation = new Runnable() {
        @Override
        public void run() {
            if (!resumed || HomeSettings.canReadWifiName(HomeActivity.this)) return;
            try {
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            } catch (Exception ignored) {
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCATION) return;
        // an empty result means the dialog was dismissed without an answer: ask again next time
        if (results.length > 0) HomeSettings.setAskedLocation(this);
        networkStatus.refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
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
        pendingEntryAnimation = true;
        openingOwnScreen = false;
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

    /**
     * Arriving home: the shelf, its label, dots and chips fade up and settle a few dp.
     * Only alpha and translation are animated, so nothing is re-laid out and the shelf's
     * measured centre stays exactly where it is.
     */
    private void playEntryAnimation() {
        float slide = ENTRY_SLIDE_DP * getResources().getDisplayMetrics().density;
        View[] views = {shelf, focusedLabel, pageDots, chipsRow};
        for (View v : views) {
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(slide);
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(ENTRY_ANIM_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void applyBackgroundIfChanged() {
        long stamp = HomeSettings.hasCustomBackground(this)
                ? HomeSettings.backgroundFile(this).lastModified() : 0L;
        if (stamp == backgroundStamp) return;
        backgroundStamp = stamp;
        HomeSettings.applyBackground(this, background);
    }

    private void updateClock() {
        Date now = new Date();
        clockTime.setText(timeFormat.format(now));
        String date = dateFormat.format(now);
        if (!date.isEmpty()) date = date.substring(0, 1).toUpperCase(uiLocale) + date.substring(1);
        clockDate.setText(date);
    }

    // ------------------------------------------------------------ status line

    /** Wi-Fi symbol and the network's name when connected; the struck-through symbol and "Bağlı değil" when not. */
    private void showNetwork(int kind, String name) {
        String label;
        int icon;
        switch (kind) {
            case NetworkStatus.WIFI:
                icon = R.drawable.ic_wifi;
                label = name != null ? name : getString(R.string.net_wifi);
                break;
            case NetworkStatus.ETHERNET:
                icon = R.drawable.ic_ethernet;
                label = getString(R.string.net_ethernet);
                break;
            case NetworkStatus.OTHER:
                icon = R.drawable.ic_wifi;
                label = getString(R.string.net_other);
                break;
            default:
                icon = R.drawable.ic_wifi_off;
                label = getString(R.string.net_none);
                break;
        }
        netIcon.setImageResource(icon);
        netLabel.setText(label);
    }

    private void showBattery(int pct) {
        if (pct < 0) {
            batteryIcon.setVisibility(View.GONE);
            batteryLabel.setVisibility(View.GONE);
            return;
        }
        int color = getColor(pct <= 20 ? R.color.state_stopped : R.color.ink_dim);
        batteryIcon.setColorFilter(color);
        batteryLabel.setTextColor(color);
        batteryLabel.setText(getString(R.string.battery_pct, pct));
        batteryIcon.setVisibility(View.VISIBLE);
        batteryLabel.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- dock

    private void rebuildDock() {
        dock.clear();
        dock.addAll(DockStore.load(this));
        pruneUninstalled();
        if (lastFocusedTop != 0) {
            // back from Kutu Home's settings: focus returns to the gear
            renderDock(KEEP_FOCUS);
            focusView(findViewById(lastFocusedTop));
        } else if (lastFocusedChip >= 0) {
            // returning from a chip panel: the shelf is rebuilt, but focus belongs on the chip
            renderDock(KEEP_FOCUS);
            focusChip(lastFocusedChip);
        } else {
            renderDock(lastFocusedPkg);
        }
    }

    /**
     * A favourite whose app has been uninstalled leaves the shelf by itself, instead of
     * staying as a dimmed "not installed" tile the human has to remove by hand. An app
     * that is only disabled is still installed and keeps its dimmed tile, because it can
     * come back with its data intact.
     */
    private void pruneUninstalled() {
        boolean changed = false;
        for (int i = dock.size() - 1; i >= 0; i--) {
            String pkg = dock.get(i)[0];
            if (AppRepository.isInstalled(this, pkg)) continue;
            dock.remove(i);
            changed = true;
            if (pkg.equals(lastFocusedPkg)) lastFocusedPkg = neighbourAt(i);
        }
        if (changed) DockStore.save(this, dock);
    }

    /** The favourite that slid into position idx, or the new last one when idx was the end. */
    private String neighbourAt(int idx) {
        if (dock.isEmpty() || idx < 0) return null;
        return dock.get(Math.min(idx, dock.size() - 1))[0];
    }

    /**
     * Guide 19 fits about seven tiles. The human may choose five to nine; up to what the
     * safe width holds the tiles keep their size, beyond it they shrink together so the
     * shelf never runs into the overscan margin.
     */
    private float computeTileScale(int slots) {
        int overscan = getResources().getDimensionPixelSize(R.dimen.overscan_h);
        int max = getResources().getDisplayMetrics().widthPixels - (overscan * 2);
        int tile = getResources().getDimensionPixelSize(R.dimen.tile_outer);
        int pads = getResources().getDimensionPixelSize(R.dimen.shelf_padding) * 2
                + getResources().getDimensionPixelSize(R.dimen.row_padding) * 2;
        float fit = (max - pads) / (float) (tile * slots);
        return Math.min(1f, fit);
    }

    private int scaled(int dimenRes) {
        return Math.round(getResources().getDimensionPixelSize(dimenRes) * tileScale);
    }

    private void sizeTile(View tile) {
        int outer = scaled(R.dimen.tile_outer);
        int inset = scaled(R.dimen.tile_inset);
        int iconPx = scaled(R.dimen.tile_icon);
        ViewGroup.LayoutParams lp = tile.getLayoutParams();
        lp.width = outer;
        lp.height = outer;
        tile.setLayoutParams(lp);
        tile.setPadding(inset, inset, inset, inset);
        View icon = tile.findViewById(R.id.tile_icon);
        FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) icon.getLayoutParams();
        ilp.width = iconPx;
        ilp.height = iconPx;
        icon.setLayoutParams(ilp);
    }

    private void renderDock(String focusPkg) {
        dockRow.removeAllViews();
        // shrink only for tiles actually on screen: nine slots holding seven apps keep full size
        tileScale = computeTileScale(Math.min(visibleTiles, Math.max(1, dock.size())));

        if (dock.isEmpty()) {
            dockRow.addView(buildAddTile());
            clampShelfWidth();
            applyFocus(focusPkg);
            return;
        }

        int iconPx = scaled(R.dimen.tile_icon);
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
            sizeTile(tile);
            ImageView icon = tile.findViewById(R.id.tile_icon);

            if (entry.available && entry.icon != null) {
                Drawable art = IconNormalizer.normalize(this, entry.pkg, entry.icon, iconPx);
                icon.setImageDrawable(art);
                // the card and its hairline take the icon's own colour
                TileGlass.apply(tile.findViewById(R.id.tile_card), entry.pkg, art);
                tile.setAlpha(1f);
            } else {
                // guide 19: a disabled (still installed) app is dimmed, never a crash
                icon.setImageDrawable(getDrawable(R.drawable.kutu_icon));
                tile.setAlpha(0.38f);
            }

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            lp.setMarginStart(i == 0 ? 0 : gap);
            tile.setLayoutParams(lp);
            tile.setTag(entry.pkg);
            tile.setNextFocusUpId(R.id.settings_button);

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
                focusView(t);
                return;
            }
        }
        focusFirstTile();
    }

    private void rememberFocus(String pkg, int chipIndex) {
        lastFocusedPkg = pkg;
        lastFocusedChip = chipIndex;
        lastFocusedTop = 0;
    }

    private void rememberTop(int viewId) {
        lastFocusedPkg = null;
        lastFocusedChip = -1;
        lastFocusedTop = viewId;
    }

    private void focusView(final View v) {
        if (v == null) {
            focusFirstTile();
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                v.requestFocus();
            }
        });
    }

    private void focusChip(final int index) {
        if (index < 0 || index >= chipsRow.getChildCount()) {
            focusFirstTile();
            return;
        }
        focusView(chipsRow.getChildAt(index));
    }

    /** Guide 19: at most N visible slots; beyond that the shelf scrolls instead of growing. */
    private void clampShelfWidth() {
        shelf.post(new Runnable() {
            @Override
            public void run() {
                int overscan = getResources().getDimensionPixelSize(R.dimen.overscan_h);
                int max = getResources().getDisplayMetrics().widthPixels - (overscan * 2);

                int tile = scaled(R.dimen.tile_outer);
                int gap = getResources().getDimensionPixelSize(R.dimen.tile_gap);
                int shelfPad = getResources().getDimensionPixelSize(R.dimen.shelf_padding) * 2;
                int rowPad = getResources().getDimensionPixelSize(R.dimen.row_padding) * 2;
                int slots = visibleTiles;
                int widest = (tile * slots) + (gap * (slots - 1)) + rowPad + shelfPad;

                int cap = Math.min(max, widest);
                ViewGroup.LayoutParams lp = shelf.getLayoutParams();
                // dockRow.getWidth() already includes its own padding
                int natural = dockRow.getWidth() + shelfPad;

                // fewer than N: shelf shrinks and stays centred
                lp.width = natural <= cap ? ViewGroup.LayoutParams.WRAP_CONTENT : cap;
                shelf.setLayoutParams(lp);
                shelf.post(new Runnable() {
                    @Override
                    public void run() {
                        buildPageDots();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ page dots

    /** One dot per page of the shelf, only while it has more favourites than it shows. */
    private void buildPageDots() {
        int pages = dock.size() > visibleTiles
                ? (dock.size() + visibleTiles - 1) / visibleTiles : 0;
        if (pages == pageDots.getChildCount()
                && (pages > 0) == (pageDots.getVisibility() == View.VISIBLE)) {
            updateActiveDot();
            return;
        }
        pageDots.removeAllViews();
        if (pages == 0) {
            pageDots.setVisibility(View.GONE);
            return;
        }
        int size = getResources().getDimensionPixelSize(R.dimen.page_dot);
        int gap = getResources().getDimensionPixelSize(R.dimen.page_dot_gap);
        for (int i = 0; i < pages; i++) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            dot.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart(gap);
            pageDots.addView(dot, lp);
        }
        pageDots.setVisibility(View.VISIBLE);
        updateActiveDot();
    }

    private void updateActiveDot() {
        int pages = pageDots.getChildCount();
        if (pages == 0) return;
        View content = shelf.getChildAt(0);
        int maxScroll = content == null ? 0
                : Math.max(0, content.getWidth() - (shelf.getWidth() - shelf.getPaddingLeft() - shelf.getPaddingRight()));
        int active = maxScroll == 0 ? 0 : Math.round(shelf.getScrollX() / (float) maxScroll * (pages - 1));
        int on = getColor(R.color.ink);
        int off = getColor(R.color.ink_dim);
        for (int i = 0; i < pages; i++) {
            GradientDrawable d = (GradientDrawable) pageDots.getChildAt(i).getBackground();
            d.setColor(i == active ? on : off);
            pageDots.getChildAt(i).setAlpha(i == active ? 1f : 0.45f);
        }
    }

    private View buildAddTile() {
        View tile = LayoutInflater.from(this).inflate(R.layout.view_tile, dockRow, false);
        sizeTile(tile);
        ImageView icon = tile.findViewById(R.id.tile_icon);
        icon.setImageDrawable(getDrawable(R.drawable.ic_add));
        tile.setTag(ADD_TILE_TAG);
        tile.setNextFocusUpId(R.id.settings_button);
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
        focusView(dockRow.getChildAt(0));
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
                        String neighbour = neighbourAt(idx);
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
        // on top of the 114% focus scale this keeps the card inside the shelf's clip box;
        // 14dp pushed its top edge past row_padding and cut it flat
        tile.setTranslationY(lifted ? -MOVE_LIFT_DP * getResources().getDisplayMetrics().density : 0f);
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
        addChip(getString(R.string.chip_mirror), R.drawable.ic_chip_mirror, new Runnable() {
            @Override
            public void run() {
                // guide 23: never open MirrorActivity; open our own panel
                openOwn(new Intent(HomeActivity.this, MirrorPanelActivity.class));
            }
        });
        addChip(getString(R.string.chip_settings), R.drawable.ic_chip_settings, new Runnable() {
            @Override
            public void run() {
                openSettings();
            }
        });
        addChip(getString(R.string.chip_transfer), R.drawable.ic_chip_transfer, new Runnable() {
            @Override
            public void run() {
                openTransfer();
            }
        });
        addChip(getString(R.string.chip_all_apps), R.drawable.ic_chip_all_apps, new Runnable() {
            @Override
            public void run() {
                openAllApps();
            }
        });
    }

    /**
     * Unlike Ekran Yansitma, this needs no panel of its own. The transfer app's own screen is
     * the session - it is what opens the port and what closes it again on BACK - so a switch
     * here would only be a second, lying copy of that state.
     *
     * Kutu Home holds no network permission and gains none from this: it starts a component in
     * another package and is told nothing about it.
     */
    private void openTransfer() {
        Intent i = new Intent();
        i.setComponent(new ComponentName(
                AppRepository.TRANSFER_PKG, "local.kutu.transfer.TransferControlActivity"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.transfer_missing, Toast.LENGTH_SHORT).show();
        }
    }

    private void addChip(String label, int iconRes, final Runnable action) {
        TextView chip = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.view_chip, chipsRow, false);
        chip.setText(label);

        // the icon reads the chip's own ink token, so it follows the theme with the text
        int iconSize = getResources().getDimensionPixelSize(R.dimen.chip_icon);
        Drawable icon = getDrawable(iconRes);
        if (icon != null) icon.setBounds(0, 0, iconSize, iconSize);
        chip.setCompoundDrawablesRelative(icon, null, null, null);
        chip.setCompoundDrawablePadding(getResources().getDimensionPixelSize(R.dimen.chip_icon_pad));

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
        attachGlassFocus(chip, CHIP_FOCUS_SCALE, new Runnable() {
            @Override
            public void run() {
                rememberFocus(null, index);
            }
        });
        chipsRow.addView(chip);
    }

    /** Opens one of Kutu Home's own screens; coming back from it replays no entry animation. */
    private void openOwn(Intent i) {
        openingOwnScreen = true;
        try {
            startActivity(i);
        } catch (Exception e) {
            openingOwnScreen = false;
        }
    }

    private void openAllApps() {
        openOwn(new Intent(this, AllAppsActivity.class));
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
