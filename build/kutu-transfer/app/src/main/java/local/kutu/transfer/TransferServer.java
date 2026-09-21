package local.kutu.transfer;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The LAN side of Kutu Transfer: one ServerSocket, a fixed worker pool, and six routes.
 *
 * Lifetime is the security model. The socket exists only while the session screen is up, and
 * {@link #idleFor()} lets the service close it after a quiet spell, so a forgotten session
 * does not leave a writable port open on a box whose firmware stopped getting patches in 2021.
 *
 * Nothing here can start an Activity. That is deliberate and load-bearing: guide 10 makes "a
 * bare TCP connection must not open UI" a stop condition, and the only way to be sure of it is
 * for the server to have no path to the UI at all.
 */
final class TransferServer {

    static final int PORT = 8787;

    /** Enough for a browser's parallel requests, few enough to bound memory on this box. */
    private static final int WORKERS = 4;
    private static final int SOCKET_TIMEOUT_MS = 30_000;
    /** Wrong-PIN attempts allowed before the session stops accepting any. */
    private static final int MAX_PIN_ATTEMPTS = 8;
    private static final String COOKIE = "kt";

    private final Context ctx;
    private final String pin;
    private final String token;
    private final AtomicLong lastRequest = new AtomicLong(System.currentTimeMillis());

    private volatile ServerSocket socket;
    private volatile boolean running;
    private volatile int pinAttempts;
    private ExecutorService pool;
    private Thread accepter;

    TransferServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        SecureRandom rnd = new SecureRandom();
        this.pin = String.format("%06d", rnd.nextInt(1_000_000));
        byte[] t = new byte[16];
        rnd.nextBytes(t);
        StringBuilder sb = new StringBuilder();
        for (byte b : t) sb.append(String.format("%02x", b));
        this.token = sb.toString();
    }

    String pin() {
        return pin;
    }

    /** Milliseconds since the last request; the service uses it for the idle timeout. */
    long idleFor() {
        return System.currentTimeMillis() - lastRequest.get();
    }

    void start() throws IOException {
        socket = new ServerSocket(PORT, 16);
        socket.setReuseAddress(true);
        running = true;
        pool = Executors.newFixedThreadPool(WORKERS);
        accepter = new Thread(this::acceptLoop, "kutu-transfer");
        accepter.setDaemon(true);
        accepter.start();
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            Socket client = null;
            try {
                client = socket.accept();
                final Socket c = client;
                c.setSoTimeout(SOCKET_TIMEOUT_MS);
                pool.execute(() -> handleQuietly(c));
            } catch (IOException e) {
                if (running) close(client);
                // a closed ServerSocket during stop() lands here; the loop exits on !running
            } catch (Exception e) {
                close(client);
            }
        }
    }

    private void handleQuietly(Socket client) {
        try {
            handle(client);
        } catch (Exception e) {
            // A dropped browser tab is routine and must never take the pool thread down, but
            // closing the socket without a word turns any real bug into a silent hang at the
            // other end. Log it, and try to say something before giving up.
            android.util.Log.w("KutuTransfer", "request failed", e);
            try {
                Http.sendText(Http.buffered(client.getOutputStream()), 500, "server error");
            } catch (Exception ignored) {
            }
        } finally {
            close(client);
        }
    }

    private void handle(Socket client) throws IOException {
        InetAddress peer = client.getInetAddress();
        OutputStream out = Http.buffered(client.getOutputStream());

        // The box is meant to stay off the internet; a non-private peer is answered by
        // nothing at all rather than by a 403 that would confirm something is here.
        if (!Net.isPrivate(peer)) return;

        lastRequest.set(System.currentTimeMillis());

        InputStream in = client.getInputStream();
        Http req = Http.parse(in);
        if (req == null) {
            Http.sendText(out, 400, "bad request");
            return;
        }

        boolean authed = isAuthed(req);

        if ("/".equals(req.path) || "/index.html".equals(req.path)) {
            servePage(out, authed);
            return;
        }
        if ("/auth".equals(req.path)) {
            handleAuth(req, out);
            return;
        }

        if (!authed) {
            Http.sendJson(out, 401, "{\"error\":\"auth\"}");
            return;
        }

        switch (req.path) {
            case "/api/list":
                handleList(req, out);
                break;
            case "/api/free":
                Http.sendJson(out, 200, "{\"free\":" + Shared.freeBytes(ctx) + "}");
                break;
            case "/dl":
                handleDownload(req, out);
                break;
            case "/up":
                handleUpload(req, out);
                break;
            case "/rm":
                handleDelete(req, out);
                break;
            default:
                Http.sendText(out, 404, "not found");
        }
    }

    // ------------------------------------------------------------------ auth

    private boolean isAuthed(Http req) {
        String given = req.cookie(COOKIE);
        return given != null && constantTimeEquals(given, token);
    }

    private void handleAuth(Http req, OutputStream out) throws IOException {
        if (!"POST".equals(req.method)) {
            Http.sendText(out, 405, "POST only");
            return;
        }
        if (pinAttempts >= MAX_PIN_ATTEMPTS) {
            Http.sendJson(out, 429, "{\"error\":\"locked\"}");
            return;
        }
        long len = req.contentLength();
        if (len < 0 || len > 256) {
            Http.sendJson(out, 400, "{\"error\":\"bad\"}");
            return;
        }
        byte[] body = new byte[(int) len];
        int read = 0;
        while (read < body.length) {
            int n = req.body.read(body, read, body.length - read);
            if (n < 0) break;
            read += n;
        }
        String given = new String(body, 0, read, "UTF-8").trim();
        if (given.startsWith("pin=")) given = Http.urlDecode(given.substring(4));

        if (!constantTimeEquals(given, pin)) {
            pinAttempts++;
            Http.sendJson(out, 401, "{\"error\":\"pin\"}");
            return;
        }
        pinAttempts = 0;
        // session cookie only: no Max-Age, so it dies with the browser and with this session
        Http.send(out, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes("UTF-8"),
                "Set-Cookie: " + COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict",
                "Cache-Control: no-store");
    }

    /** Length-independent only for equal-length inputs, which is all we compare. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    // ------------------------------------------------------------------ routes

    private void servePage(OutputStream out, boolean authed) throws IOException {
        byte[] page = Page.html(authed);
        Http.send(out, 200, "text/html; charset=utf-8", page, "Cache-Control: no-store");
    }

    /**
     * One folder per request, named by its virtual path. An empty path lists the roots
     * themselves, so the browser always has somewhere to start and never has to know a real
     * filesystem path.
     *
     * Each entry carries its own full virtual path rather than just a name. The page therefore
     * never joins path strings itself, which is one whole class of escaping bug that simply
     * cannot happen.
     */
    private void handleList(Http req, OutputStream out) throws IOException {
        String p = req.query.get("p");
        if (p == null) p = "";

        if (p.isEmpty()) {
            List<Shared.Root> roots = Shared.roots(ctx);
            StringBuilder sb = new StringBuilder("{\"path\":\"\",\"parent\":null")
                    .append(",\"writable\":false")
                    .append(",\"card\":").append(Shared.canBrowseCard(ctx))
                    .append(",\"free\":").append(Shared.freeBytes(ctx))
                    .append(",\"crumbs\":[]")
                    .append(",\"entries\":[");
            for (int i = 0; i < roots.size(); i++) {
                Shared.Root r = roots.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"n\":\"").append(Http.jsonEscape(r.label))
                        .append("\",\"p\":\"").append(Http.jsonEscape(r.id))
                        .append("\",\"d\":true,\"s\":0,\"t\":0}");
            }
            Http.sendJson(out, 200, sb.append("]}").toString());
            return;
        }

        Shared.Node node = Shared.resolve(ctx, p);
        if (node == null || !node.file.isDirectory()) {
            Http.sendJson(out, 404, "{\"error\":\"missing\"}");
            return;
        }

        File[] kids = node.file.listFiles();
        boolean denied = kids == null;                 // a folder we are not allowed to read
        List<File> entries = new ArrayList<>();
        if (kids != null) {
            for (File f : kids) entries.add(f);
            entries.sort((a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
        }
        boolean truncated = entries.size() > Shared.MAX_ENTRIES;
        if (truncated) entries = entries.subList(0, Shared.MAX_ENTRIES);

        StringBuilder sb = new StringBuilder("{\"path\":\"")
                .append(Http.jsonEscape(node.vpath)).append('"')
                .append(",\"parent\":\"").append(Http.jsonEscape(Shared.parentOf(node.vpath)))
                .append('"')
                .append(",\"writable\":").append(node.writable() && node.file.canWrite())
                .append(",\"card\":").append(Shared.canBrowseCard(ctx))
                .append(",\"denied\":").append(denied)
                .append(",\"truncated\":").append(truncated)
                .append(",\"free\":").append(Shared.freeBytes(node.file))
                .append(",\"crumbs\":[");
        appendCrumbs(sb, node);
        sb.append("],\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            File f = entries.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"n\":\"").append(Http.jsonEscape(f.getName()))
                    .append("\",\"p\":\"")
                    .append(Http.jsonEscape(node.vpath + "/" + f.getName()))
                    .append("\",\"d\":").append(f.isDirectory())
                    .append(",\"s\":").append(f.isDirectory() ? 0 : f.length())
                    .append(",\"t\":").append(f.lastModified()).append('}');
        }
        Http.sendJson(out, 200, sb.append("]}").toString());
    }

    /** Root label first, then one crumb per segment, each with the path it navigates to. */
    private void appendCrumbs(StringBuilder sb, Shared.Node node) {
        String[] parts = node.vpath.split("/");
        StringBuilder walk = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(',');
                walk.append('/');
            }
            walk.append(parts[i]);
            String label = i == 0 ? node.root.label : parts[i];
            sb.append("{\"p\":\"").append(Http.jsonEscape(walk.toString()))
                    .append("\",\"n\":\"").append(Http.jsonEscape(label)).append("\"}");
        }
    }

    private void handleDownload(Http req, OutputStream out) throws IOException {
        Shared.Node node = Shared.resolve(ctx, req.query.get("f"));
        if (node == null || !node.file.isFile()) {
            Http.sendText(out, 404, "not found");
            return;
        }
        File f = node.file;
        long size = f.length();
        long from = 0, to = size - 1;

        // Range is what lets a browser scrub a video served straight off the box.
        String range = req.header("range");
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    String lo = spec.substring(0, dash).trim();
                    String hi = spec.substring(dash + 1).trim();
                    if (lo.isEmpty()) {
                        long tail = Long.parseLong(hi);
                        from = Math.max(0, size - tail);
                    } else {
                        from = Long.parseLong(lo);
                        if (!hi.isEmpty()) to = Math.min(Long.parseLong(hi), size - 1);
                    }
                    partial = true;
                } catch (NumberFormatException e) {
                    partial = false;
                }
            }
        }
        if (from < 0 || from >= size || to < from) {
            Http.send(out, 416, null, null, "Content-Range: bytes */" + size);
            return;
        }

        long length = to - from + 1;
        StringBuilder head = Http.head(partial ? 206 : 200, Shared.mimeOf(f.getName()), length,
                "Accept-Ranges: bytes",
                "Content-Disposition: attachment; filename=\"" + f.getName() + "\"");
        if (partial) {
            head.insert(head.length() - 2, "Content-Range: bytes " + from + "-" + to + "/" + size
                    + "\r\n");
        }
        out.write(head.toString().getBytes("UTF-8"));

        try (FileInputStream fis = new FileInputStream(f)) {
            skipFully(fis, from);
            byte[] buf = new byte[64 * 1024];
            long left = length;
            while (left > 0) {
                int n = fis.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
                lastRequest.set(System.currentTimeMillis());
            }
        }
        out.flush();
    }

    private void handleUpload(Http req, OutputStream out) throws IOException {
        if (!"POST".equals(req.method)) {
            Http.sendText(out, 405, "POST only");
            return;
        }
        String boundary = Multipart.boundaryOf(req.header("content-type"));
        if (boundary == null) {
            Http.sendJson(out, 400, "{\"error\":\"not multipart\"}");
            return;
        }

        // Where the browser is standing, not a fixed folder. This is the whole point of the
        // change: "I uploaded something and cannot see where it went" was true because there
        // was only ever one answer.
        String where = req.query.get("p");
        Shared.Node dest = Shared.resolve(ctx, where == null ? "" : where);
        if (dest == null) dest = Shared.resolve(ctx, Shared.ROOT_APP);
        if (dest == null || !dest.file.isDirectory()) {
            Http.sendJson(out, 404, "{\"error\":\"missing\"}");
            return;
        }
        if (!dest.writable() || !dest.file.canWrite()) {
            Http.sendJson(out, 403, "{\"error\":\"readonly\"}");
            return;
        }
        final File into = dest.file;

        long declared = req.contentLength();
        if (declared > 0 && !Shared.hasRoom(into, declared)) {
            Http.sendJson(out, 507, "{\"error\":\"space\"}");
            return;
        }

        final List<String> saved = new ArrayList<>();
        final boolean[] outOfSpace = {false};

        Multipart.parse(req.body, boundary, new Multipart.Sink() {
            File target;

            @Override
            public OutputStream open(String fileName) throws IOException {
                String safe = Shared.sanitize(fileName);
                if (safe == null) return null;
                target = Shared.unique(into, safe);
                return new FileOutputStream(target);
            }

            @Override
            public void close(OutputStream os, String fileName, long bytes, boolean complete) {
                if (os == null) return;
                try {
                    os.close();
                } catch (IOException ignored) {
                }
                if (target == null) return;
                // a truncated upload is a half file; do not leave it looking finished
                if (!complete || !Shared.hasRoom(into, 0)) {
                    if (!Shared.hasRoom(into, 0)) outOfSpace[0] = true;
                    target.delete();
                } else {
                    saved.add(target.getName());
                }
                target = null;
            }

            @Override
            public void field(String name, String value) {
                // the form carries no fields; the PIN travels as a cookie
            }
        });

        if (outOfSpace[0]) {
            Http.sendJson(out, 507, "{\"error\":\"space\"}");
            return;
        }
        StringBuilder sb = new StringBuilder("{\"saved\":[");
        for (int i = 0; i < saved.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(Http.jsonEscape(saved.get(i))).append('"');
        }
        Http.sendJson(out, 200, sb.append("]}").toString());
    }

    private void handleDelete(Http req, OutputStream out) throws IOException {
        if (!"POST".equals(req.method) && !"DELETE".equals(req.method)) {
            Http.sendText(out, 405, "POST or DELETE");
            return;
        }
        Shared.Node node = Shared.resolve(ctx, req.query.get("f"));
        if (node == null || !node.file.exists()) {
            Http.sendJson(out, 404, "{\"error\":\"missing\"}");
            return;
        }
        // A directory goes only if it is already empty. Recursive delete driven from a browser
        // is a different and much larger promise than this app makes.
        if (node.file.isDirectory()) {
            String[] kids = node.file.list();
            if (kids == null || kids.length > 0) {
                Http.sendJson(out, 409, "{\"error\":\"notempty\"}");
                return;
            }
        }
        if (!node.writable()) {
            Http.sendJson(out, 403, "{\"error\":\"readonly\"}");
            return;
        }
        Http.sendJson(out, 200, node.file.delete() ? "{\"ok\":true}" : "{\"error\":\"denied\"}");
    }

    // ------------------------------------------------------------------ helpers

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) return;
                left--;
            } else {
                left -= s;
            }
        }
    }

    private static void close(Socket s) {
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
    }
}
