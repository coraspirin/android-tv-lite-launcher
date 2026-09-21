package local.kutu.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Guide 19: the dock lives in SharedPreferences under the stable key home/dock_v1.
 * It persists the ordered package list plus the last-known human-readable label for
 * each favourite, so a favourite whose app disappears still shows a real name.
 *
 * Seeding only ever happens when no saved dock exists (guide 16), so an APK update
 * never overwrites a layout the human arranged.
 */
final class DockStore {

    private static final String PREFS = "home";
    private static final String KEY = "dock_v1";
    private static final String ROW_SEP = "\n";
    private static final String FIELD_SEP = "\u001f";

    /** Guide 16: initial dock chosen by the human, in this order. */
    private static final String[][] SEED = {
            {"com.google.android.youtube.tv", "YouTube"},
            {"com.stremio.one", "Stremio"},
            {"com.wbd.stream", "Max"},
            {"com.apple.atve.androidtv.appletv", "Apple TV"},
            {"com.trt.tabii.android", "tabii"},
            {"com.turkcell.ott", "Turkcell TV+"},
            {"com.android.vending", "Play Store"},
    };

    private DockStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Ordered favourites as {package, rememberedLabel} pairs. */
    static List<String[]> load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.contains(KEY)) {
            List<String[]> seeded = new ArrayList<>();
            for (String[] row : SEED) seeded.add(new String[]{row[0], row[1]});
            save(ctx, seeded);
            return seeded;
        }

        List<String[]> out = new ArrayList<>();
        String raw = p.getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;

        for (String line : raw.split(ROW_SEP, -1)) {
            if (line.isEmpty()) continue;
            int cut = line.indexOf(FIELD_SEP);
            if (cut < 0) {
                out.add(new String[]{line, ""});
            } else {
                out.add(new String[]{line.substring(0, cut), line.substring(cut + 1)});
            }
        }
        return out;
    }

    static void save(Context ctx, List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            if (row == null || row.length == 0 || row[0] == null || row[0].isEmpty()) continue;
            if (sb.length() > 0) sb.append(ROW_SEP);
            sb.append(row[0]).append(FIELD_SEP).append(row.length > 1 && row[1] != null ? row[1] : "");
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    static boolean contains(List<String[]> rows, String pkg) {
        for (String[] row : rows) {
            if (row != null && row.length > 0 && row[0].equals(pkg)) return true;
        }
        return false;
    }

    static int indexOf(List<String[]> rows, String pkg) {
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row != null && row.length > 0 && row[0].equals(pkg)) return i;
        }
        return -1;
    }

    static void remove(List<String[]> rows, String pkg) {
        int i = indexOf(rows, pkg);
        if (i >= 0) rows.remove(i);
    }
}
