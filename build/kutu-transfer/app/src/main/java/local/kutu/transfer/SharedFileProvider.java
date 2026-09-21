package local.kutu.transfer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands one file in the shared folder to Android's package installer, read-only.
 *
 * Written rather than inherited: this app has no dependencies, so androidx's FileProvider is
 * not available to it. Kutu Mirror deleted its FileProvider on the grounds that nothing was
 * saved to disk (UPSTREAM-AUDIT: "no saved screen/audio content"). This app does save, so the
 * capability comes back - narrowed to a single directory, read-only, and not exported.
 *
 * The path check is the whole security of it: a caller can only ever name a file that
 * canonicalises to somewhere inside one of the roots Shared is willing to reach.
 */
public final class SharedFileProvider extends ContentProvider {

    static final String AUTHORITY = "local.kutu.transfer.files";

    static Uri uriFor(String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(name).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String vpath = uri.getLastPathSegment();
        Shared.Node node = Shared.resolve(getContext(), vpath);
        if (node == null || !node.file.isFile()) {
            throw new FileNotFoundException(String.valueOf(vpath));
        }
        return node.file;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)) {
            throw new FileNotFoundException("read-only");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** The installer reads DISPLAY_NAME and SIZE before it opens the stream. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        try {
            return Shared.mimeOf(resolve(uri).getName());
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    // Nothing writes through this provider; the browser writes through the HTTP server.

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
