package local.kutu.transfer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;

/**
 * A multipart/form-data reader that never holds a file in memory.
 *
 * This is the one piece of this app that had to be written carefully rather than simply. The
 * box has under 2 GiB of RAM and roughly 1 GiB free, so buffering an uploaded video - the
 * exact thing this app exists to accept - would kill it. Bytes are scanned for the part
 * boundary in a fixed window and written straight through to disk as they arrive.
 *
 * The window holds back the last (boundary length - 1) bytes on every pass, because a
 * boundary can straddle two reads. Only what is already known not to contain it is flushed.
 */
final class Multipart {

    private static final int BUF = 64 * 1024;
    /** A non-file form field is never legitimately larger than this. */
    private static final int MAX_FIELD = 8 * 1024;

    interface Sink {
        /** Somewhere to put a file part, or null to discard it. */
        OutputStream open(String fileName) throws IOException;

        /** Called once per opened part, whether or not it arrived complete. */
        void close(OutputStream out, String fileName, long bytes, boolean complete);

        void field(String name, String value);
    }

    private final PushbackInputStream in;
    private final byte[] delim;      // CRLF + "--" + boundary
    private final byte[] first;      // "--" + boundary

    private Multipart(InputStream raw, String boundary) {
        this.in = new PushbackInputStream(raw, BUF * 2);
        this.first = ("--" + boundary).getBytes();
        this.delim = ("\r\n--" + boundary).getBytes();
    }

    /** Extracts the boundary from a Content-Type header, or null if it is not multipart. */
    static String boundaryOf(String contentType) {
        if (contentType == null) return null;
        if (!contentType.toLowerCase().startsWith("multipart/form-data")) return null;
        for (String part : contentType.split(";")) {
            String p = part.trim();
            if (p.toLowerCase().startsWith("boundary=")) {
                String b = p.substring("boundary=".length()).trim();
                if (b.length() > 1 && b.startsWith("\"") && b.endsWith("\"")) {
                    b = b.substring(1, b.length() - 1);
                }
                return b.isEmpty() ? null : b;
            }
        }
        return null;
    }

    static void parse(InputStream body, String boundary, Sink sink) throws IOException {
        new Multipart(body, boundary).run(sink);
    }

    private void run(Sink sink) throws IOException {
        // skip whatever precedes the first boundary
        if (!copyUntil(first, null, new long[1])) return;

        while (true) {
            int a = in.read();
            int b = in.read();
            if (a < 0 || b < 0) return;
            if (a == '-' && b == '-') return;          // closing boundary
            if (a != '\r' || b != '\n') return;        // malformed

            String disposition = null;
            String line;
            while ((line = readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-disposition:")) disposition = line;
            }
            if (line == null) return;

            String fileName = valueOf(disposition, "filename");
            String fieldName = valueOf(disposition, "name");
            long[] count = new long[1];

            if (fileName != null && !fileName.isEmpty()) {
                OutputStream out = sink.open(fileName);
                boolean complete = false;
                try {
                    complete = copyUntil(delim, out, count);
                } finally {
                    sink.close(out, fileName, count[0], complete);
                }
                if (!complete) return;
            } else {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                boolean complete = copyUntil(delim, new BoundedStream(buf, MAX_FIELD), count);
                if (fieldName != null) sink.field(fieldName, buf.toString("UTF-8"));
                if (!complete) return;
            }
        }
    }

    /**
     * Copies until the pattern is found, writing everything before it to out. Whatever
     * followed the pattern is pushed back for the next part to read.
     */
    private boolean copyUntil(byte[] pattern, OutputStream out, long[] written)
            throws IOException {
        byte[] buf = new byte[BUF];
        int have = 0;
        while (true) {
            // Never ask for more than is already there. PushbackInputStream.read(b,off,len)
            // drains its pushback buffer and then calls through to the socket for whatever
            // is still missing - so when a whole small body already sits in pushback, asking
            // for the full window blocks forever on bytes the client already finished
            // sending. A large upload hides this, because more data is always in flight.
            int want = buf.length - have;
            int avail = in.available();
            if (avail > 0 && avail < want) want = avail;

            int n = in.read(buf, have, want);
            if (n < 0) {
                if (out != null && have > 0) {
                    out.write(buf, 0, have);
                    written[0] += have;
                }
                return false;                                  // EOF before the boundary
            }
            have += n;

            int at = indexOf(buf, have, pattern);
            if (at >= 0) {
                if (out != null && at > 0) {
                    out.write(buf, 0, at);
                    written[0] += at;
                }
                int after = at + pattern.length;
                if (have > after) in.unread(buf, after, have - after);
                return true;
            }

            // hold back enough that a boundary split across two reads is still seen
            int keep = Math.min(have, pattern.length - 1);
            int flush = have - keep;
            if (flush > 0) {
                if (out != null) {
                    out.write(buf, 0, flush);
                    written[0] += flush;
                }
                System.arraycopy(buf, flush, buf, 0, keep);
                have = keep;
            } else if (have == buf.length) {
                throw new IOException("boundary longer than the window");
            }
        }
    }

    private static int indexOf(byte[] hay, int len, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= len; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 4096) throw new IOException("part header too long");
        }
        return null;
    }

    /** Pulls a quoted attribute out of a Content-Disposition line. */
    private static String valueOf(String header, String key) {
        if (header == null) return null;
        String needle = key + "=\"";
        int i = header.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = header.indexOf('"', start);
        return end < 0 ? null : header.substring(start, end);
    }

    /** Drops anything past the cap, so an oversized form field cannot grow the heap. */
    private static final class BoundedStream extends OutputStream {
        private final OutputStream target;
        private final int cap;
        private int seen;

        BoundedStream(OutputStream target, int cap) {
            this.target = target;
            this.cap = cap;
        }

        @Override
        public void write(int b) throws IOException {
            if (seen++ < cap) target.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int room = cap - seen;
            if (room > 0) target.write(b, off, Math.min(room, len));
            seen += len;
        }
    }
}
