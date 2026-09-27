package local.kutu.transfer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Receives Android's answer to an update install started by UpdateActivity.
 *
 * Not exported: only the PendingIntent this app handed to PackageInstaller can reach it. That
 * matters, because on "needs confirmation" it starts the Intent it was given - an exported
 * component doing that would let any app on the box have us start an arbitrary activity.
 */
public final class UpdateStatusReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(confirm);
                    return;
                } catch (Exception ignored) {
                }
            }
            UpdateActivity.onInstallResult(false, null);
            return;
        }
        UpdateActivity.onInstallResult(status == PackageInstaller.STATUS_SUCCESS,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }
}
