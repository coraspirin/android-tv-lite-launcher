package local.kutu.home;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Focus feel and physical-remote key handling for a tile.
 *
 * Guide 18: focused tile brightens, scales ~114%, lifts, carries a tasteful accent
 * glow, and its label is shown. Short 150-180 ms animations. The resting icon is
 * never tinted, because the glow lives on the tile's background and shadow only.
 *
 * Guide 20: long press is driven from raw key events, not from
 * setOnLongClickListener alone. DPAD_CENTER, ENTER and the gamepad A button are all
 * handled, together with repeat events and the system long-press flag. A long press
 * must never fall through into launching the app, so ACTION_DOWN is always consumed
 * and the launch only ever happens on ACTION_UP when no long press fired.
 */
final class TileBehaviour {

    interface Callbacks {
        void onShortPress(View tile);

        void onLongPress(View tile);

        void onFocusChanged(View tile, boolean focused);
    }

    private static final long LONG_PRESS_MS = 520L;
    private static final long ANIM_MS = 165L;
    private static final float FOCUS_SCALE = 1.14f;
    /**
     * Kept modest so the glow fits inside the shelf's clip box, and cheap on the
     * S905X GPU. Guide 18: on weak GPUs reduce glow and elevation before anything else.
     */
    private static final float FOCUS_LIFT_DP = 6f;

    private TileBehaviour() {
    }

    static void attach(final View tile, final Callbacks cb) {
        final float lift = FOCUS_LIFT_DP * tile.getResources().getDisplayMetrics().density;
        final int glow = tile.getResources().getColor(R.color.accent_glow, null);

        // The focusable view is an inset box; the card inside it is what grows. Scaling
        // the card rather than the box keeps the whole effect within the bounds Android
        // scrolls into view, so a tile at either end of the shelf is never clipped.
        View inner = tile.findViewById(R.id.tile_card);
        final View card = inner != null ? inner : tile;

        card.setOutlineSpotShadowColor(glow);
        card.setOutlineAmbientShadowColor(glow);

        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float scale = hasFocus ? FOCUS_SCALE : 1f;
                card.animate()
                        .scaleX(scale).scaleY(scale)
                        .translationZ(hasFocus ? lift : 0f)
                        .setDuration(ANIM_MS)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                // Never bringToFront() here: in a LinearLayout that reorders the
                // child and the dock silently rearranges itself on focus.
                // translationZ already lifts the tile above its neighbours.
                cb.onFocusChanged(v, hasFocus);
            }
        });

        final State state = new State();
        final Runnable longFire = new Runnable() {
            @Override
            public void run() {
                if (state.longFired) return;
                state.longFired = true;
                cb.onLongPress(tile);
            }
        };

        tile.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (!isSelectKey(keyCode)) return false;

                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        state.longFired = false;
                        state.downAt = SystemClock.uptimeMillis();
                        event.startTracking();
                        v.removeCallbacks(longFire);
                        v.postDelayed(longFire, LONG_PRESS_MS);
                    } else if (!state.longFired
                            && (event.isLongPress()
                            || SystemClock.uptimeMillis() - state.downAt >= LONG_PRESS_MS)) {
                        v.removeCallbacks(longFire);
                        longFire.run();
                    }
                    return true;   // always consume, so nothing launches early
                }

                if (event.getAction() == KeyEvent.ACTION_UP) {
                    v.removeCallbacks(longFire);
                    if (state.longFired) {
                        state.longFired = false;
                        return true;   // swallow: a long press never launches
                    }
                    cb.onShortPress(v);
                    return true;
                }
                return false;
            }
        });

        // Pointer/accessibility parity; the key path above is the one that matters on a remote.
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (state.longFired) {
                    state.longFired = false;
                    return;
                }
                cb.onShortPress(v);
            }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (state.longFired) return true;
                state.longFired = true;
                cb.onLongPress(v);
                return true;
            }
        });
    }

    static boolean isSelectKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static final class State {
        boolean longFired;
        long downAt;
    }
}
