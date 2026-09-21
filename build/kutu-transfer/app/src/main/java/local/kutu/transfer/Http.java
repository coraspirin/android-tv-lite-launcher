package local.kutu.transfer;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Just enough HTTP/1.1 to serve one page, list a folder and move files both ways.
 *
 * Hand-written because this app carries no dependencies, in line with the other two. The
 * surface is small and entirely under our control, which is the point: every byte read off a
 * socket here is bounded before it is believed.
 */
final class Http {

    /** A request line plus headers must fit in this, or the connection is dropped. */
    private static final int MAX_HEAD = 16 * 1024;
    private static final int MAX_LINE = 8 * 1024;

    final String method;
    final String path;
    final Map<String, String> query = new HashMap<>();
    final Map<String, String> headers = new HashMap<>();
    final InputStream body;

    private Http(String method, String path, InputStream body) {
        this.method = method;
        this.path = path;
        this.body = body;
    }

    /** Reads the request head. Returns null on a malformed or oversized request. */
    static Http parse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;

        String target = parts[1];
        String path = target;
        String qs = null;
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            qs = target.substring(q + 1);
        }

        Http r = new Http(parts[0], urlDecode(path), in);
        if (qs != null) {
            for (String pair : qs.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                if (eq < 0) r.query.put(urlDecode(pair), "");
                else r.query.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }

        int total = line.length();
        while (true) {
            String h = readLine(in);
            if (h == null) return null;
            if (h.isEmpty()) break;
            total += h.length();
            if (total > MAX_HEAD) return null;
            int c = h.indexOf(':');
            if (c > 0) {
                r.headers.put(h.substring(0, c).trim().toLowerCase(), h.substring(c + 1).trim());
            }
        }
        return r;
    }

    String header(String name) {
        return headers.get(name.toLowerCase());
    }

    long contentLength() {
        try {
            return Long.parseLong(header("content-length"));
        } catch (Exception e) {
            return -1;
        }
    }

    /** The value of one cookie, or null. */
    String cookie(String name) {
        String raw = header("cookie");
        if (raw == null) return null;
        for (String part : raw.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(name)) return p.substring(eq + 1);
        }
        return null;
    }

    // ------------------------------------------------------------------ responses

    static void send(OutputStream out, int code, String type, byte[] body, String... extra)
            throws IOException {
        StringBuilder sb = head(code, type, body == null ? 0 : body.length, extra);
        out.write(sb.toString().getBytes("UTF-8"));
        if (body != null) out.write(body);
        out.flush();
    }

    static void sendText(OutputStream out, int code, String text) throws IOException {
        send(out, code, "text/plain; charset=utf-8", text.getBytes("UTF-8"));
    }

    static void sendJson(OutputStream out, int code, String json) throws IOException {
        send(out, code, "application/json; charset=utf-8", json.getBytes("UTF-8"),
                "Cache-Control: no-store");
    }

    static StringBuilder head(int code, String type, long length, String... extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n");
        // this page is a LAN tool, not a site: keep it out of caches and out of frames
        sb.append("X-Content-Type-Options: nosniff\r\n");
        sb.append("X-Frame-Options: DENY\r\n");
        sb.append("Referrer-Policy: no-referrer\r\n");
        for (String e : extra) sb.append(e).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        return sb;
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 303: return "See Other";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 507: return "Insufficient Storage";
            default:  return "Internal Server Error";
        }
    }

    static OutputStream buffered(OutputStream raw) {
        return new BufferedOutputStream(raw, 32 * 1024);
    }

    // ------------------------------------------------------------------ helpers

    /** Reads one CRLF-terminated line without over-reading into the body. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > MAX_LINE) return null;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    /** Minimal JSON string escaping; file names are the only untrusted text we emit. */
    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
