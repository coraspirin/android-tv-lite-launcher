package local.kutu.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Kutu Clock, the box's screen saver.
 *
 * The setup disables the stock screen savers (dreams.basic, backdrop). With no valid
 * dream Android does not show a screen saver at all: it puts the box to sleep the moment
 * the screen saver should start, and CEC turns the TV off with it. This dream fills that
 * slot so the box sleeps only when the user's own "Put device to sleep" time runs out.
 *
 * A black screen with the time and date, dimmed, redrawn once a minute by
 * ACTION_TIME_TICK and moved to a new spot each time so nothing burns in. No animation,
 * no network, no wakelock: the system binds this service only while dreaming.
 */
public class KutuDream extends DreamService {

    private static final int INK = 0xFFD8D8D8;
    private static final int INK_DIM = 0xFF8A8A8A;

    private final Random random = new Random();
    private FrameLayout root;
    private LinearLayout block;
    private TextView clockTime;
    private TextView clockDate;
    private SimpleDateFormat timeFormat;
    private SimpleDateFormat dateFormat;
    private boolean receiverOn;

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
            moveBlock();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);

        // the same words and order as the home screen clock
        Locale uiLocale = getResources().getConfiguration().getLocales().get(0);
        timeFormat = new SimpleDateFormat("HH:mm", uiLocale);
        dateFormat = new SimpleDateFormat(getString(R.string.date_pattern), uiLocale);

        clockTime = new TextView(this);
        clockTime.setTextColor(INK);
        clockTime.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.dream_time));

        clockDate = new TextView(this);
        clockDate.setTextColor(INK_DIM);
        clockDate.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.dream_date));

        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.addView(clockTime);
        block.addView(clockDate);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(block, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        // the first spot is picked once the sizes are known
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) moveBlock();
            }
        });
        setContentView(root);
        updateClock();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockReceiver, filter);
        receiverOn = true;
        updateClock();
    }

    @Override
    public void onDreamingStopped() {
        stopClock();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopClock();
        super.onDetachedFromWindow();
    }

    private void stopClock() {
        if (!receiverOn) return;
        receiverOn = false;
        try {
            unregisterReceiver(clockReceiver);
        } catch (Exception ignored) {
        }
    }

    private void updateClock() {
        if (clockTime == null) return;
        Date now = new Date();
        clockTime.setText(timeFormat.format(now));
        clockDate.setText(dateFormat.format(now));
    }

    /** Anywhere the whole block fits, a margin in from the edges for overscanning TVs. */
    private void moveBlock() {
        if (root == null || block == null) return;
        int margin = root.getWidth() / 20;
        int freeX = root.getWidth() - block.getWidth() - 2 * margin;
        int freeY = root.getHeight() - block.getHeight() - 2 * margin;
        if (freeX <= 0 || freeY <= 0) return;
        block.setTranslationX(margin + random.nextInt(freeX));
        block.setTranslationY(margin + random.nextInt(freeY));
    }
}
