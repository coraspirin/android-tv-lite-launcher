package local.kutu.transfer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The places this app can see, and the rules for turning a path that arrived over the network
 * into a real File.
 *
 * Originally this was one folder - the app's own external files dir, which needs no permission
 * on API 28. That answered "put a file on the box" but not "which of the box's files do I
 * want", so browsing came second and the model grew a root list:
 *
 *   kutu/...  the app's own folder. Always present, needs no permission, survives nothing
 *             being granted.
 *   sd/...    the whole internal card, present only while READ/WRITE_EXTERNAL_STORAGE is
 *             granted. This is the wide one, and it is why the PIN, the private-peer filter
 *             and the session lifetime matter more than they did before.
 *
 * Everything the client says travels as a virtual path, "<rootId>/<relative>", never as a
 * filesystem path. An empty virtual path means the root list itself.
 */
final class Shared {

    /** Refuse an upload that would leave the box with less than this. */
    private static final long FREE_FLOOR_BYTES = 256L * 1024L * 1024L;

    /** A folder with more entries than this is served truncated rather than not at all. */
    static final int MAX_ENTRIES = 2000;

    static final String ROOT_APP = "kutu";
    static final String ROOT_SD = "sd";

    private Shared() {
    }

    /** One browsable top-level place. */
    static final class Root {
        final String id;
        final String label;
        final File dir;
        final boolean writable;

        Root(String id, String label, File dir, boolean writable) {
            this.id = id;
            this.label = label;
            this.dir = dir;
            this.writable = writable;
        }
    }

    /** A resolved location: the file, the root it belongs to, and its canonical virtual path. */
    static final class Node {
        final Root root;
        final File file;
        final String vpath;

        Node(Root root, File file, String vpath) {
            this.root = root;
            this.file = file;
            this.vpath = vpath;
        }

        boolean writable() {
            return root.writable;
        }
    }

    // ------------------------------------------------------------------ roots

    /** The app's own folder. Present whether or not any permission was granted. */
    static File dir(Context ctx) {
        File f = ctx.getExternalFilesDir(null);
        if (f == null) f = ctx.getFilesDir();     // no external storage mounted
        if (!f.exists()) f.mkdirs();
        return f;
    }

    /**
     * True once the human has granted storage access on the TV. Without it the root list is
     * just the app folder, which is exactly how this app shipped first - so a refusal costs
     * the browsing and nothing else.
     */
    static boolean canBrowseCard(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean canWriteCard(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    static List<Root> roots(Context ctx) {
        List<Root> out = new ArrayList<>();
        out.add(new Root(ROOT_APP, "Kutu Klasörü", dir(ctx), true));

        if (canBrowseCard(ctx)
                && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File sd = Environment.getExternalStorageDirectory();
            if (sd != null && sd.isDirectory()) {
                out.add(new Root(ROOT_SD, "Dahili Depolama", sd, canWriteCard(ctx)));
            }
        }
        return out;
    }

    static Root rootById(Context ctx, String id) {
        for (Root r : roots(ctx)) if (r.id.equals(id)) return r;
        return null;
    }

    // ------------------------------------------------------------------ resolution

    /**
     * Resolves a virtual path, or null if it names nothing we are willing to reach.
     *
     * Canonicalising and then re-checking against the root is the part that matters: stripping
     * ".." by hand misses encodings, symlinks and "....//", whereas the canonical path either
     * sits under the root or it does not. The virtual path handed back is rebuilt from the
     * canonical one, so what the browser then displays and sends back is already normalised.
     */
    static Node resolve(Context ctx, String vpath) {
        if (vpath == null) return null;
        String v = vpath.replace('\\', '/');
        if (v.indexOf('\u0000') >= 0) return null;
        while (v.startsWith("/")) v = v.substring(1);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (v.isEmpty()) return null;                 // the root list is not a Node

        int slash = v.indexOf('/');
        String id = slash < 0 ? v : v.substring(0, slash);
        String rel = slash < 0 ? "" : v.substring(slash + 1);

        Root root = rootById(ctx, id);
        if (root == null) return null;

        File target = rel.isEmpty() ? root.dir : new File(root.dir, rel);
        try {
            String base = root.dir.getCanonicalPath();
            String path = target.getCanonicalPath();
            if (!path.equals(base) && !path.startsWith(base + File.separator)) return null;

            String tail = path.length() > base.length() ? path.substring(base.length() + 1) : "";
            String canonical = tail.isEmpty() ? root.id : root.id + "/" + tail.replace('\\', '/');
            return new Node(root, new File(path), canonical);
        } catch (IOException e) {
            return null;
        }
    }

    /** The virtual path of the folder containing this one, or "" for a root. */
    static String parentOf(String vpath) {
        if (vpath == null) return "";
        int slash = vpath.lastIndexOf('/');
        return slash < 0 ? "" : vpath.substring(0, slash);
    }

    // ------------------------------------------------------------------ writing

    /** Strips a multipart filename down to something safe to create. */
    static String sanitize(String raw) {
        if (raw == null) return null;
        // browsers may send a path; keep only the last segment
        int cut = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String name = cut >= 0 ? raw.substring(cut + 1) : raw;
        name = name.replaceAll("[\\x00-\\x1f/\\\\:*?\"<>|]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 180) name = name.substring(name.length() - 180);
        return name.isEmpty() ? null : name;
    }

    /** Adds " (2)", " (3)" and so on rather than overwriting what is already there. */
    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String stem = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 10000; i++) {
            f = new File(dir, stem + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    /** Free space on the volume the given folder sits on. */
    static long freeBytes(File dir) {
        try {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            return fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
        } catch (Exception e) {
            return Long.MAX_VALUE;   // unknown: do not block the transfer on a bad stat
        }
    }

    static long freeBytes(Context ctx) {
        return freeBytes(dir(ctx));
    }

    /** True when there is room for one more byte written; checked again as bytes land. */
    static boolean hasRoom(File dir, long extra) {
        return freeBytes(dir) - extra > FREE_FLOOR_BYTES;
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
