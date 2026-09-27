package local.kutu.home;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Whether a local TCP port is listening, read from /proc/net/tcp[6].
 *
 * That file needs no permission on API 28, so the mirror panel and the chip status dots
 * can show the receiver's real state without Kutu Home holding any network permission.
 * Newer Android restricts it; callers then get null and show a neutral state.
 */
final class PortState {

    static final int AIRPLAY_PORT = 7000;
    static final int TRANSFER_PORT = 8787;

    private PortState() {
    }

    /** @return TRUE listening, FALSE not listening, null when the state cannot be read */
    static Boolean isListening(int port) {
        Boolean any = null;
        for (String path : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            Boolean r = scan(path, port);
            if (r == null) continue;
            any = (any == null) ? r : (any || r);
        }
        return any;
    }

    private static Boolean scan(String path, int port) {
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
