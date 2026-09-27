package local.kutu.home;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;

/**
 * The Wi-Fi line under the clock.
 *
 * Event driven: a default-network callback is registered while the home screen is
 * started and dropped when it stops, exactly like the clock's TIME_TICK receiver, so
 * nothing runs while the network is steady. There is no signal-strength bar on purpose:
 * RSSI changes arrive every few seconds and would keep waking the launcher.
 *
 * Reads connectivity only; the launcher still has no INTERNET permission. Android 9 hides
 * the Wi-Fi network's name from apps without location access (this box's firmware also
 * blanks it in NetworkInfo), so the name comes from WifiManager and needs
 * ACCESS_WIFI_STATE plus ACCESS_COARSE_LOCATION, with the box's location setting on. If
 * any of that is missing the line says "Wi-Fi bağlı" instead of a name.
 */
final class NetworkStatus {

    static final int NONE = 0;
    static final int WIFI = 1;
    static final int ETHERNET = 2;
    static final int OTHER = 3;

    interface Listener {
        /**
         * @param kind NONE, WIFI, ETHERNET or OTHER
         * @param name the Wi-Fi network's name, or null when unknown
         */
        void onNetworkStatus(int kind, String name);
    }

    private final ConnectivityManager cm;
    private final WifiManager wifi;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean registered;

    private final Runnable publish = new Runnable() {
        @Override
        public void run() {
            read();
        }
    };

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            post();
        }

        @Override
        public void onLost(Network network) {
            post();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            post();
        }
    };

    NetworkStatus(Context ctx, Listener listener) {
        this.cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.listener = listener;
    }

    void start() {
        read();
        if (cm == null || registered) return;
        try {
            cm.registerDefaultNetworkCallback(callback);
            registered = true;
        } catch (Exception ignored) {
            // without the callback the line still shows the state it had when the screen opened
        }
    }

    void stop() {
        main.removeCallbacks(publish);
        if (cm == null || !registered) return;
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (Exception ignored) {
        }
        registered = false;
    }

    /** re-reads now, e.g. once location access was granted and the name became readable */
    void refresh() {
        read();
    }

    /** callbacks arrive on the connectivity thread, and often several at once */
    private void post() {
        main.removeCallbacks(publish);
        main.post(publish);
    }

    private void read() {
        if (cm == null) {
            listener.onNetworkStatus(NONE, null);
            return;
        }
        Network n;
        NetworkCapabilities caps;
        try {
            n = cm.getActiveNetwork();
            caps = n == null ? null : cm.getNetworkCapabilities(n);
        } catch (Exception e) {
            n = null;
            caps = null;
        }
        if (n == null || caps == null) {
            listener.onNetworkStatus(NONE, null);
            return;
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            listener.onNetworkStatus(WIFI, wifiName(n));
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            listener.onNetworkStatus(ETHERNET, null);
        } else {
            listener.onNetworkStatus(OTHER, null);
        }
    }

    /** WifiManager first (needs location access), NetworkInfo's extra info as a fallback. */
    @SuppressWarnings("deprecation")
    private String wifiName(Network n) {
        String name = null;
        try {
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            name = clean(info == null ? null : info.getSSID());
        } catch (Exception ignored) {
        }
        if (name != null) return name;
        try {
            NetworkInfo info = cm.getNetworkInfo(n);
            return clean(info == null ? null : info.getExtraInfo());
        } catch (Exception e) {
            return null;
        }
    }

    /** strips the quotes Android puts round an SSID; null for the "unknown" placeholders */
    private static String clean(String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        if (raw.isEmpty() || raw.equalsIgnoreCase("<unknown ssid>") || raw.startsWith("0x")) return null;
        return raw;
    }
}
