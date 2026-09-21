package local.kutu.transfer;

import android.app.Activity;
import android.os.Bundle;

/**
 * The other half of the guide 11 pair: stops the session and finishes. Same contract - no
 * parameters, no commands, nothing drawn.
 *
 * Stopping the service is enough to close the port. TransferActivity notices the session has
 * gone on its next tick and shows that rather than a stale address.
 */
public final class TransferStopActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        TransferService.stop(this);
        finish();
    }
}
