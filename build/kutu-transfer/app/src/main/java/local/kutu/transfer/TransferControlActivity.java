package local.kutu.transfer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Guide 11 shim. Exported only because Kutu Home is a separate package and has to be able to
 * reach it; it takes no parameters, accepts no commands, opens the session screen and finishes
 * before it can be drawn.
 */
public final class TransferControlActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent i = new Intent(this, TransferActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }
}
