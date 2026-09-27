package local.kutu.home;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Set;
import java.util.UUID;

/**
 * The remote's battery level, read straight from the remote.
 *
 * On this box nothing in Android reports it: the system battery is a fixed dummy
 * (ro.boot.fake_battery) and Android 9's HID host does not surface a BLE remote's level.
 * But a BLE remote is a HID-over-GATT device, and HOGP makes the standard Battery Service
 * mandatory, so the level is one GATT read away: Battery Service 0x180F, Battery Level
 * 0x2A19. Android lets a second GATT client share the link the HID host already holds.
 *
 * Cheap by construction: at most one attempt per {@link #RETRY_MS}, started only when the
 * home screen comes back, closed as soon as it answers or {@link #TIMEOUT_MS} passes.
 * If the remote has no such service the indicator simply never appears.
 *
 * Needs BLUETOOTH, a normal install-time permission.
 */
final class RemoteBattery {

    interface Listener {
        /** @param pct 0-100, or -1 to hide the indicator */
        void onRemoteBattery(int pct);
    }

    private static final String TAG = "KutuHome";
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    private static final long RETRY_MS = 30L * 60L * 1000L;
    private static final long TIMEOUT_MS = 8000L;
    /** a reading older than this is no longer shown */
    private static final long STALE_MS = 24L * 60L * 60L * 1000L;

    private static boolean inFlight;

    private RemoteBattery() {
    }

    /** Shows the last good reading now, and refreshes it in the background when due. */
    static void refreshIfDue(Context ctx, final Listener listener) {
        final Context app = ctx.getApplicationContext();
        listener.onRemoteBattery(current(app));

        long now = System.currentTimeMillis();
        long tried = HomeSettings.remoteBatteryTriedAt(app);
        if (inFlight || (tried > 0 && now - tried < RETRY_MS && now >= tried)) return;

        BluetoothDevice remote = findRemote();
        if (remote == null) return;

        HomeSettings.setRemoteBatteryTriedAt(app, now);
        inFlight = true;
        new Reader(app, remote, listener).start();
    }

    private static int current(Context app) {
        int pct = HomeSettings.remoteBattery(app);
        long at = HomeSettings.remoteBatteryAt(app);
        if (pct < 0 || System.currentTimeMillis() - at > STALE_MS) return -1;
        return pct;
    }

    /** A bonded BLE peripheral: on a TV box that is the remote. */
    private static BluetoothDevice findRemote() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return null;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return null;
            for (BluetoothDevice d : bonded) {
                int type = d.getType();
                if (type != BluetoothDevice.DEVICE_TYPE_LE && type != BluetoothDevice.DEVICE_TYPE_DUAL) continue;
                BluetoothClass cls = d.getBluetoothClass();
                int major = cls == null ? BluetoothClass.Device.Major.UNCATEGORIZED : cls.getMajorDeviceClass();
                if (major == BluetoothClass.Device.Major.PERIPHERAL
                        || major == BluetoothClass.Device.Major.UNCATEGORIZED) {
                    return d;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "remote battery: cannot list bonded devices", e);
        }
        return null;
    }

    private static final class Reader extends BluetoothGattCallback {
        private final Context app;
        private final BluetoothDevice device;
        private final Listener listener;
        private final Handler main = new Handler(Looper.getMainLooper());
        private BluetoothGatt gatt;
        private boolean done;

        private final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                finish(-1, "timeout");
            }
        };

        Reader(Context app, BluetoothDevice device, Listener listener) {
            this.app = app;
            this.device = device;
            this.listener = listener;
        }

        void start() {
            main.postDelayed(timeout, TIMEOUT_MS);
            try {
                gatt = device.connectGatt(app, false, this, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) finish(-1, "connectGatt returned null");
            } catch (Exception e) {
                finish(-1, "connectGatt failed: " + e);
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) finish(-1, "discoverServices refused");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                finish(-1, "disconnected, status " + status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(BATTERY_SERVICE);
            BluetoothGattCharacteristic level = svc == null ? null : svc.getCharacteristic(BATTERY_LEVEL);
            if (level == null) {
                finish(-1, "no battery service on " + device.getName());
                return;
            }
            if (!g.readCharacteristic(level)) finish(-1, "readCharacteristic refused");
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            byte[] v = c.getValue();
            if (status != BluetoothGatt.GATT_SUCCESS || v == null || v.length == 0) {
                finish(-1, "read failed, status " + status);
                return;
            }
            finish(Math.max(0, Math.min(100, v[0] & 0xFF)), null);
        }

        private void finish(final int pct, String why) {
            synchronized (this) {
                if (done) return;
                done = true;
            }
            main.removeCallbacks(timeout);
            if (gatt != null) {
                try {
                    gatt.disconnect();
                    gatt.close();
                } catch (Exception ignored) {
                }
            }
            if (pct >= 0) {
                Log.i(TAG, "remote battery: " + pct + "% (" + device.getName() + ")");
                HomeSettings.setRemoteBattery(app, pct, System.currentTimeMillis());
            } else {
                Log.i(TAG, "remote battery: unavailable - " + why);
            }
            main.post(new Runnable() {
                @Override
                public void run() {
                    inFlight = false;
                    listener.onRemoteBattery(current(app));
                }
            });
        }
    }
}
