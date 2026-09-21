package local.kutu.transfer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.StateSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * What the session looks like from the sofa: the address to type, the code to type it with,
 * and whatever has arrived so far.
 *
 * This screen IS the session. Opening it starts the server, leaving it stops the server, so
 * the port cannot outlive what the human can see. That is also what makes guide 10 hold by
 * construction rather than by care: nothing on the network can reach this class, and this
 * class is the only thing that creates the socket.
 *
 * Views are built in code rather than inflated. There is no shared resource module between the
 * Kutu apps, and one screen is not worth a layout file plus a colour set that would then have
 * to be kept in step with the launcher by hand.
 */
public final class TransferActivity extends Activity {

    private static final long TICK_MS = 1000L;
    private static final int REQ_STORAGE = 41;

    private TextView urlView;
    private TextView pinView;
    private TextView scopeView;
    private LinearLayout list;
    private TextView empty;
    private String listSignature = "";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        TransferService.start(this);
        askForStorage();
        handler.post(tick);
    }

    /**
     * Asked for here, on the screen the human opened deliberately, and never anywhere else.
     * Without it the browser sees only this app's own folder; with it, the whole card. The
     * dialog is D-pad navigable on the TV, and a refusal is a working app with less in it.
     */
    private void askForStorage() {
        if (Shared.canBrowseCard(this) && Shared.canWriteCard(this)) return;
        try {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        } catch (Exception ignored) {
            // no dialog available: the app still works against its own folder
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            String pin = TransferService.currentPin();
            String ip = Net.localAddress();

            if (ip == null) {
                urlView.setText(R.string.session_no_wifi);
                pinView.setText("");
            } else if (pin == null) {
                urlView.setText(R.string.session_failed);   // port taken, or still starting
                pinView.setText("");
            } else {
                urlView.setText("http://" + ip + ":" + TransferServer.PORT);
                pinView.setText(pin);
            }
            scopeView.setText(Shared.canBrowseCard(TransferActivity.this)
                    ? R.string.scope_full : R.string.scope_limited);
            refreshList();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        // leaving the screen is what closes the port; there is no other way to keep it open
        TransferService.stop(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ file list

    /**
     * Rebuilt only when the set of files actually changes. Rebuilding on every tick would
     * throw away whatever row the human had selected, once a second.
     */
    private void refreshList() {
        File[] files = Shared.dir(this).listFiles();
        if (files == null) files = new File[0];
        File[] only = Arrays.stream(files).filter(File::isFile).toArray(File[]::new);
        Arrays.sort(only, Comparator.comparingLong(File::lastModified).reversed());

        StringBuilder sig = new StringBuilder();
        for (File f : only) sig.append(f.getName()).append(':').append(f.length()).append('|');
        if (sig.toString().equals(listSignature)) return;
        listSignature = sig.toString();

        list.removeAllViews();
        empty.setVisibility(only.length == 0 ? View.VISIBLE : View.GONE);
        for (File f : only) list.addView(row(f));
    }

    private View row(final File f) {
        TextView t = new TextView(this);
        t.setText(f.getName() + "   ·   " + human(f.length()));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(0xFFDCE6F2);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setPadding(dp(18), dp(11), dp(18), dp(11));
        t.setFocusable(true);
        t.setBackground(rowBackground());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);

        t.setOnClickListener(v -> open(f));
        return t;
    }

    /**
     * Hands the file to whatever the system uses for its type. An APK lands on Android's own
     * installer, which still shows its confirmation screen - the install is never silent, and
     * never initiated by anything arriving over the network.
     */
    private void open(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(SharedFileProvider.uriFor(Shared.ROOT_APP + "/" + f.getName()),
                    Shared.mimeOf(f.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static String human(long n) {
        if (n < 1024) return n + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double d = n;
        int i = -1;
        do {
            d /= 1024;
            i++;
        } while (d >= 1024 && i < 3);
        return String.format(java.util.Locale.US, "%.1f %s", d, u[i]);
    }

    // ------------------------------------------------------------------ ui

    private ViewGroup buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1622);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(64), dp(40), dp(64), dp(28));

        col.addView(label(getString(R.string.session_title), 24, 0xFFEDF2F8, 0, Gravity.CENTER));
        col.addView(label(getString(R.string.session_hint), 14, 0xFF94A5BB, dp(4), Gravity.CENTER));

        // address and code side by side, so the list below gets the room
        LinearLayout band = new LinearLayout(this);
        band.setOrientation(LinearLayout.HORIZONTAL);
        band.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bandLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bandLp.topMargin = dp(16);
        band.setLayoutParams(bandLp);

        urlView = label("", 30, 0xFFEDF2F8, 0, Gravity.CENTER);
        urlView.setTypeface(Typeface.MONOSPACE);
        band.addView(urlView);

        pinView = label("", 30, 0xFF7FC4F5, 0, Gravity.CENTER);
        pinView.setTypeface(Typeface.MONOSPACE);
        pinView.setLetterSpacing(0.18f);
        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(dp(14));
        pill.setColor(0x1AFFFFFF);
        pill.setStroke(dp(1), 0x33FFFFFF);
        pinView.setBackground(pill);
        pinView.setPadding(dp(20), dp(4), dp(20), dp(8));
        LinearLayout.LayoutParams pinLp =
                (LinearLayout.LayoutParams) pinView.getLayoutParams();
        pinLp.leftMargin = dp(28);
        band.addView(pinView);

        col.addView(band);

        scopeView = label("", 13, 0xFF7C8DA4, dp(14), Gravity.CENTER);
        col.addView(scopeView);

        empty = label(getString(R.string.session_empty), 14, 0xFF6C7D93, dp(30), Gravity.CENTER);
        col.addView(empty);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(18);
        scroll.setLayoutParams(scrollLp);
        scroll.setVerticalScrollBarEnabled(false);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(list);
        col.addView(scroll);

        col.addView(label(getString(R.string.session_close_hint), 13, 0xFF6C7D93,
                dp(10), Gravity.CENTER));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private StateListDrawable rowBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.RECTANGLE);
        focused.setCornerRadius(dp(12));
        focused.setColor(0x267FC4F5);
        focused.setStroke(dp(2), 0xFF7FC4F5);

        GradientDrawable resting = new GradientDrawable();
        resting.setShape(GradientDrawable.RECTANGLE);
        resting.setCornerRadius(dp(12));
        resting.setColor(0x0DFFFFFF);
        resting.setStroke(dp(1), 0x1FFFFFFF);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(StateSet.WILD_CARD, resting);
        return sl;
    }

    private TextView label(String text, int sp, int colour, int marginTop, int gravity) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setGravity(gravity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = marginTop;
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
