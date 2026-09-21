package local.kutu.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Owns the socket for exactly as long as a session lasts.
 *
 * A foreground service because the socket has to survive the box moving focus around, and a
 * notification is the honest way to say a port is open. It takes no wakelock and has no boot
 * receiver: this app only ever runs because someone asked it to, seconds ago, from the sofa.
 *
 * The idle timer is the other half of that promise. A session left open by someone who walked
 * away closes itself, so a writable LAN port on a box with 2021 firmware is never long-lived.
 */
public final class TransferService extends Service {

    private static final String CHANNEL = "kutu_transfer";
    private static final int NOTIF_ID = 42;

    /** No request for this long and the session closes itself. */
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long IDLE_CHECK_MS = 30 * 1000L;

    private static volatile TransferService instance;

    private TransferServer server;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** The PIN to print on the TV, or null when nothing is running. */
    static String currentPin() {
        TransferService s = instance;
        return s != null && s.server != null ? s.server.pin() : null;
    }

    static boolean isRunning() {
        return instance != null && instance.server != null;
    }

    static void start(Context ctx) {
        Intent i = new Intent(ctx, TransferService.class);
        ctx.startForegroundService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, TransferService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground();
        if (server == null) {
            TransferServer s = new TransferServer(this);
            try {
                s.start();
                server = s;
                handler.postDelayed(idleCheck, IDLE_CHECK_MS);
            } catch (Exception e) {
                // port taken or no network: do not sit here pretending to be a session
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (server == null) return;
            if (server.idleFor() > IDLE_TIMEOUT_MS) {
                stopSelf();
                return;
            }
            handler.postDelayed(this, IDLE_CHECK_MS);
        }
    };

    private void promoteToForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        // deliberately no content intent: the notification must not be a route into the UI
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(idleCheck);
        if (server != null) {
            server.stop();
            server = null;
        }
        instance = null;
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
