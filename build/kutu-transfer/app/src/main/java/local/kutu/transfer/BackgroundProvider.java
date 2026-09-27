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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lets Kutu Home read the photos in the "Arka planlar" folder, so the human can pick one as
 * the launcher's background. Kutu Home holds no storage permission and gains none from this.
 *
 * Deliberately the narrowest thing that works:
 *   - one folder only, its direct children only, no subfolders
 *   - image files only (jpg, jpeg, png, webp)
 *   - read only: openFile refuses every mode but "r"; insert, update and delete throw
 *   - a name is a single path segment and must canonicalise back into the folder
 *
 * It is exported without a permission because the two apps are signed with separate keys, so a
 * signature permission cannot tie them together. The consequence, disclosed in the security
 * review: any app on the box can read the photos the human put in this one folder.
 *
 * Content URIs: content://local.kutu.transfer.backgrounds/ lists the folder (DISPLAY_NAME,
 * SIZE, date_modified, newest first); content://local.kutu.transfer.backgrounds/NAME opens one.
 */
public final class BackgroundProvider extends ContentProvider {

    static final String AUTHORITY = "local.kutu.transfer.backgrounds";
    private static final String COL_MODIFIED = "date_modified";
    private static final String[] DEFAULT_COLS = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, COL_MODIFIED};

    @Override
    public boolean onCreate() {
        return true;
    }

    static boolean isImage(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    private File folder() {
        return Shared.backgroundsDir(getContext());
    }

    /** The one file a URI names, or an exception for anything else. */
    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 1) throw new FileNotFoundException("not a single file");
        String name = seg.get(0);
        if (name.isEmpty() || name.startsWith(".") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0 || !isImage(name)) {
            throw new FileNotFoundException("not allowed");
        }
        File dir = folder();
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())) {
                throw new FileNotFoundException("outside the folder");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("unresolvable");
        }
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String[] cols = projection != null ? projection : DEFAULT_COLS;
        MatrixCursor c = new MatrixCursor(cols);

        List<File> files = new ArrayList<>();
        if (uri.getPathSegments().isEmpty()) {
            File[] all = folder().listFiles();
            if (all != null) {
                for (File f : all) if (f.isFile() && !f.getName().startsWith(".") && isImage(f.getName())) files.add(f);
            }
            files.sort(Comparator.comparingLong(File::lastModified).reversed());
        } else {
            try {
                files.add(resolve(uri));
            } catch (FileNotFoundException e) {
                return c;
            }
        }

        for (File f : files) {
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                else if (COL_MODIFIED.equals(cols[i])) row[i] = f.lastModified();
                else row[i] = null;
            }
            c.addRow(row);
        }
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

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
