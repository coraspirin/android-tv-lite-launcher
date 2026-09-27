package local.kutu.transfer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.StateSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Updates the three Kutu apps from the project's GitHub Releases.
 *
 * Kutu Home reaches this screen from its settings; it holds no network permission and gains
 * none. This app already has INTERNET (for the transfer server) and REQUEST_INSTALL_PACKAGES
 * (for handing a received APK to Android's installer), so it is the natural carrier.
 *
 * Nothing happens in the background: the network is used only while this screen is open, and
 * only because the human opened it. Each update is checked before Android is asked to install:
 *   1. SHA-256 of the download equals the one in the release's kutu-versions.json
 *   2. the APK's package name is the one expected, and its versionCode is newer
 *   3. the APK is signed with the same certificate as the installed app
 * Android enforces 3 itself; checking first turns a cryptic installer failure into a clear
 * message. The install always goes through Android's own confirmation screen.
 *
 * Exported so Kutu Home can open it; it takes no parameters and accepts no commands.
 */
public final class UpdateActivity extends Activity {

    static final String REPO = "coraspirin/android-tv-lite-launcher";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String VERSIONS_ASSET = "kutu-versions.json";
    private static final String USER_AGENT = "KutuAktarim-Updater";
    private static final long MAX_APK_BYTES = 64L * 1024L * 1024L;

    /** mirror first and this app last: updating itself ends this screen */
    private static final String[] APP_PKGS = {"local.kutu.mirror", "local.kutu.home", "local.kutu.transfer"};
    private static final int[] APP_NAMES = {R.string.name_mirror, R.string.name_home, R.string.name_transfer};

    /** package and display name, in the system language */
    private String[][] apps() {
        String[][] out = new String[APP_PKGS.length][];
        for (int i = 0; i < APP_PKGS.length; i++) out[i] = new String[]{APP_PKGS[i], getString(APP_NAMES[i])};
        return out;
    }

    /** the screen currently showing, so the install-status receiver can report back */
    private static UpdateActivity visible;

    private TextView status;
    private LinearLayout list;
    private boolean busy;

    /** per package: {versionCode, versionName, downloadUrl, sha256} from the release */
    private final Map<String, Object[]> available = new HashMap<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        check();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = this;
        if (!available.isEmpty()) render();   // back from the installer: versions may have moved
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (visible == this) visible = null;
    }

    // --------------------------------------------------------------- checking

    private void check() {
        busy = true;
        status.setText(R.string.update_checking);
        new Thread(() -> {
            int msg = 0;
            String tag = null;
            try {
                JSONObject release = new JSONObject(new String(get(LATEST, "application/vnd.github+json"), "UTF-8"));
                tag = release.optString("tag_name", "");
                Map<String, String> assets = new HashMap<>();
                JSONArray arr = release.getJSONArray("assets");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.getJSONObject(i);
                    assets.put(a.getString("name"), a.getString("browser_download_url"));
                }
                String versionsUrl = assets.get(VERSIONS_ASSET);
                if (versionsUrl == null) {
                    msg = R.string.update_no_manifest;
                } else {
                    JSONObject apps = new JSONObject(new String(get(versionsUrl, null), "UTF-8"))
                            .getJSONObject("apps");
                    available.clear();
                    for (String[] app : apps()) {
                        JSONObject o = apps.optJSONObject(app[0]);
                        if (o == null) continue;
                        String url = assets.get(o.getString("asset"));
                        if (url == null) continue;
                        available.put(app[0], new Object[]{
                                o.getLong("versionCode"), o.getString("versionName"),
                                url, o.getString("sha256").toLowerCase()});
                    }
                }
            } catch (NotFound e) {
                msg = R.string.update_no_release;
            } catch (Exception e) {
                msg = R.string.update_offline;
            }
            final int fmsg = msg;
            final String ftag = tag;
            runOnUiThread(() -> {
                busy = false;
                if (fmsg != 0) {
                    status.setText(fmsg);
                } else {
                    status.setText(getString(R.string.update_latest, ftag));
                }
                render();
            });
        }, "update-check").start();
    }

    private void render() {
        list.removeAllViews();
        View firstAction = null;
        for (final String[] app : apps()) {
            long installedCode = -1;
            String installedName = null;
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(app[0], 0);
                installedCode = pi.getLongVersionCode();
                installedName = pi.versionName;
            } catch (Exception ignored) {
            }
            Object[] a = available.get(app[0]);

            String text;
            boolean action = false;
            if (installedCode < 0) {
                text = getString(R.string.update_row_missing, app[1]);
            } else if (a != null && (Long) a[0] > installedCode) {
                text = getString(R.string.update_row_new, app[1], installedName, a[1]);
                action = true;
            } else {
                text = getString(R.string.update_row_current, app[1], installedName);
            }

            TextView row = row(text, action);
            if (action) {
                row.setOnClickListener(v -> update(app[0], app[1]));
                if (firstAction == null) firstAction = row;
            }
            list.addView(row);
        }
        if (firstAction != null) {
            final View f = firstAction;
            f.post(f::requestFocus);
        }
    }

    // --------------------------------------------------------------- updating

    private void update(final String pkg, final String label) {
        if (busy) return;
        final Object[] a = available.get(pkg);
        if (a == null) return;
        busy = true;
        status.setText(getString(R.string.update_downloading, label));
        new Thread(() -> {
            File dir = new File(getCacheDir(), "updates");
            deleteTree(dir);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File apk = new File(dir, pkg + ".apk");
            int err = 0;
            try {
                String sha = download((String) a[2], apk);
                if (!sha.equals(a[3])) {
                    err = R.string.update_bad_hash;
                } else {
                    err = verify(pkg, apk, (Long) a[0]);
                }
                if (err == 0) install(pkg, apk);
            } catch (Exception e) {
                err = R.string.update_offline;
            }
            final int ferr = err;
            runOnUiThread(() -> {
                if (ferr != 0) {
                    busy = false;
                    status.setText(ferr);
                    deleteTree(dir);
                } else {
                    status.setText(getString(R.string.update_confirm, label));
                }
            });
        }, "update-" + pkg).start();
    }

    /** @return 0 when the APK may be installed, otherwise the message to show */
    @SuppressWarnings("deprecation")
    private int verify(String pkg, File apk, long expectedCode) {
        PackageManager pm = getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (archive == null || !pkg.equals(archive.packageName)) return R.string.update_wrong_package;
        if (archive.getLongVersionCode() != expectedCode) return R.string.update_wrong_package;
        try {
            PackageInfo installed = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (archive.getLongVersionCode() <= installed.getLongVersionCode()) return R.string.update_not_newer;
            if (!sameSigners(archive.signatures, installed.signatures)) return R.string.update_bad_signature;
        } catch (PackageManager.NameNotFoundException e) {
            return R.string.update_wrong_package;
        }
        return 0;
    }

    private static boolean sameSigners(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return false;
        String[] x = new String[a.length];
        String[] y = new String[b.length];
        for (int i = 0; i < a.length; i++) {
            x[i] = a[i].toCharsString();
            y[i] = b[i].toCharsString();
        }
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    /**
     * Streams the APK into a PackageInstaller session and commits it. Android answers with
     * "needs the human's confirmation", which UpdateStatusReceiver turns into the installer's
     * own confirmation screen.
     */
    private void install(String pkg, File apk) throws Exception {
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(this, UpdateStatusReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, id, result, PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pi.getIntentSender());
        }
    }

    /** Called by UpdateStatusReceiver once Android has finished or refused an install. */
    static void onInstallResult(boolean success, String message) {
        UpdateActivity a = visible;
        if (a == null) return;
        a.busy = false;
        deleteTree(new File(a.getCacheDir(), "updates"));
        if (success) {
            a.status.setText(R.string.update_done);
        } else {
            a.status.setText(a.getString(R.string.update_failed, message == null ? "" : message));
        }
        a.render();
    }

    // --------------------------------------------------------------- network

    private static final class NotFound extends Exception {
    }

    private static byte[] get(String url, String accept) throws Exception {
        HttpURLConnection c = open(url, accept);
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new NotFound();
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1024 * 1024) throw new Exception("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** @return lowercase hex SHA-256 of what was written */
    private static String download(String url, File to) throws Exception {
        HttpURLConnection c = open(url, null);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_APK_BYTES) throw new Exception("download too large");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } finally {
            c.disconnect();
        }
    }

    /** HTTPS only; GitHub's download links redirect to another HTTPS host, which is followed. */
    private static HttpURLConnection open(String url, String accept) throws Exception {
        if (!url.startsWith("https://")) throw new Exception("not https");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        if (accept != null) c.setRequestProperty("Accept", accept);
        return c;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // --------------------------------------------------------------- ui

    private ViewGroup buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1622);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(64), dp(48), dp(64), dp(28));

        col.addView(label(getString(R.string.update_title), 24, 0xFFEDF2F8, 0));
        status = label("", 15, 0xFF94A5BB, dp(10));
        col.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28);
        list.setLayoutParams(lp);
        col.addView(list);

        col.addView(label(getString(R.string.update_hint), 13, 0xFF6C7D93, dp(24)));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private TextView row(String text, boolean action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTextColor(action ? 0xFFEDF2F8 : 0xFF7C8DA4);
        t.setPadding(dp(20), dp(13), dp(20), dp(13));
        t.setFocusable(action);
        t.setBackground(rowBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private StateListDrawable rowBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(12));
        focused.setColor(0x267FC4F5);
        focused.setStroke(dp(2), 0xFF7FC4F5);

        GradientDrawable resting = new GradientDrawable();
        resting.setCornerRadius(dp(12));
        resting.setColor(0x0DFFFFFF);
        resting.setStroke(dp(1), 0x1FFFFFFF);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(StateSet.WILD_CARD, resting);
        return sl;
    }

    private TextView label(String text, int sp, int colour, int marginTop) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = marginTop;
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
