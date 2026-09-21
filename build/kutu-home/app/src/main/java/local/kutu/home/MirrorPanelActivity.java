package local.kutu.home;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Guide 23: the in-launcher Screen Mirroring panel.
 *
 * This screen never opens Kutu Mirror's MirrorActivity. The only things it may start
 * are the two control shims, which do nothing but start or stop the receiver service.
 *
 * Receiver state is read from /proc/net/tcp[6], which needs no permission. Kutu Home
 * holds no network permission and must never gain one just to show a status dot, so
 * when the file cannot be read the panel degrades to a neutral state and leaves the
 * switch wherever the user put it.
 */
public final class MirrorPanelActivity extends Activity {

    private static final int AIRPLAY_PORT = 7000;
    private static final String RECEIVER_CONTROL = "local.kutu.mirror.ReceiverControlActivity";
    private static final String RECEIVER_STOP = "local.kutu.mirror.ReceiverStopActivity";

    /** the receiver needs a moment to bind or release port 7000 before it can be re-read */
    private static final long SETTLE_MS = 1400L;

    private TextView state;
    private Switch toggle;

    /** set while the switch is being synced to the real state, so it is not read back as a user action */
    private boolean syncing;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_mirror_panel);
        state = findViewById(R.id.mirror_state);
        toggle = findViewById(R.id.mirror_toggle);

        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (syncing) return;
                setReceiverRunning(checked);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void refreshState() {
        if (!AppRepository.isInstalled(this, AppRepository.MIRROR_PKG)) {
            state.setText(R.string.mirror_state_missing);
            state.setTextColor(getColor(R.color.state_missing));
            toggle.setVisibility(View.GONE);
            return;
        }

        toggle.setVisibility(View.VISIBLE);
        Boolean listening = isPortListening(AIRPLAY_PORT);

        if (listening == null) {
            state.setText(R.string.mirror_state_unknown);
            state.setTextColor(getColor(R.color.state_unknown));
        } else if (listening) {
            state.setText(R.string.mirror_state_ready);
            state.setTextColor(getColor(R.color.state_ready));
            setCheckedSilently(true);
        } else {
            state.setText(R.string.mirror_state_stopped);
            state.setTextColor(getColor(R.color.state_stopped));
            setCheckedSilently(false);
        }
        focusToggle();
    }

    private void setCheckedSilently(boolean checked) {
        if (toggle.isChecked() == checked) return;
        syncing = true;
        toggle.setChecked(checked);
        syncing = false;
    }

    private void focusToggle() {
        toggle.post(new Runnable() {
            @Override
            public void run() {
                toggle.requestFocus();
            }
        });
    }

    /** Guide 23: never MirrorActivity, only the control shims. */
    private void setReceiverRunning(boolean running) {
        Intent i = new Intent();
        i.setComponent(new ComponentName(
                AppRepository.MIRROR_PKG, running ? RECEIVER_CONTROL : RECEIVER_STOP));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.mirror_toggle_failed, Toast.LENGTH_SHORT).show();
            setCheckedSilently(!running);
            return;
        }
        // do not trust the request: re-read the port and let the real state win
        toggle.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshState();
            }
        }, SETTLE_MS);
    }

    /**
     * @return TRUE listening, FALSE not listening, null when the state cannot be read
     * (newer Android restricts /proc/net/tcp; the panel then shows neutral).
     */
    private Boolean isPortListening(int port) {
        Boolean any = null;
        for (String path : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            Boolean r = scanProcNet(path, port);
            if (r == null) continue;
            any = (any == null) ? r : (any || r);
        }
        return any;
    }

    private Boolean scanProcNet(String path, int port) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            String line = reader.readLine();   // header
            if (line == null) return null;
            String hexPort = String.format("%04X", port);
            while ((line = reader.readLine()) != null) {
                String[] cols = line.trim().split("\\s+");
                if (cols.length < 4) continue;
                int colon = cols[1].lastIndexOf(':');
                if (colon < 0) continue;
                if (!cols[1].substring(colon + 1).equalsIgnoreCase(hexPort)) continue;
                if ("0A".equalsIgnoreCase(cols[3])) return Boolean.TRUE;   // TCP_LISTEN
            }
            return Boolean.FALSE;
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
