package local.kutu.home;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Background picker: the bundled default, plus every photo the human has sent to Kutu
 * Aktarım's "Arka planlar" folder.
 *
 * Kutu Home holds no storage and no network permission, and this keeps it that way. The
 * photos come through Kutu Aktarım's read-only BackgroundProvider, which lists that one
 * folder and opens its images for reading, nothing else. The choice is always made here,
 * on the TV: an upload alone never changes the screen (guide 10).
 *
 * A chosen photo is turned once into a screen-sized JPEG in Kutu Home's private files,
 * already rotated and centre-cropped, so the home screen only decodes a 1080p image and
 * the background survives even if Kutu Aktarım is removed later.
 */
public final class BackgroundActivity extends Activity {

    static final Uri PROVIDER = Uri.parse("content://local.kutu.transfer.backgrounds/");

    private static final float FOCUS_SCALE = 1.06f;

    private GridLayout grid;
    private ScrollView scroll;
    private TextView message;
    private boolean busy;

    @Override
    protected void attachBaseContext(Context base) {
        applyOverrideConfiguration(ThemeStore.override(base, ThemeStore.isDark(base)));
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_background);
        grid = findViewById(R.id.bg_grid);
        scroll = findViewById(R.id.bg_scroll);
        message = findViewById(R.id.bg_message);
        HomeSettings.applyBackground(this, (ImageView) findViewById(R.id.background));
        render();
    }

    private void render() {
        grid.removeAllViews();
        grid.setColumnCount(columns());

        String current = HomeSettings.backgroundSource(this);
        View first = addThumb(null, getString(R.string.bg_default), current == null);

        List<String> photos = listPhotos();
        if (photos == null) {
            message.setText(R.string.bg_transfer_missing);
        } else if (photos.isEmpty()) {
            message.setText(R.string.bg_empty);
        } else {
            message.setText(null);
            for (String name : photos) addThumb(name, name, name.equals(current));
        }

        View target = first;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (c.findViewById(R.id.thumb_current).getVisibility() == View.VISIBLE) target = c;
        }
        final View focus = target;
        focus.post(new Runnable() {
            @Override
            public void run() {
                focus.requestFocus();
            }
        });
        loadThumbnails();
    }

    private int columns() {
        int overscan = getResources().getDimensionPixelSize(R.dimen.overscan_h);
        int avail = getResources().getDisplayMetrics().widthPixels - overscan * 2
                - scroll.getPaddingLeft() - scroll.getPaddingRight();
        int cell = getResources().getDimensionPixelSize(R.dimen.bg_thumb_w)
                + Math.round(24 * getResources().getDisplayMetrics().density);
        return Math.max(1, avail / cell);
    }

    /** @return photo names, empty when the folder is empty, null when Kutu Aktarım is absent */
    private List<String> listPhotos() {
        Cursor c = null;
        try {
            c = getContentResolver().query(PROVIDER, null, null, null, null);
            if (c == null) return null;
            List<String> out = new ArrayList<>();
            int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            while (col >= 0 && c.moveToNext()) {
                String name = c.getString(col);
                if (name != null && !name.isEmpty()) out.add(name);
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    private View addThumb(final String name, final String label, boolean current) {
        final View cell = LayoutInflater.from(this).inflate(R.layout.view_bg_thumb, grid, false);
        cell.setTag(name);
        cell.findViewById(R.id.thumb_current).setVisibility(current ? View.VISIBLE : View.GONE);
        final View card = cell.findViewById(R.id.thumb_card);
        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                float s = focused ? FOCUS_SCALE : 1f;
                card.animate().scaleX(s).scaleY(s).setDuration(TileBehaviour.ANIM_MS)
                        .setInterpolator(new DecelerateInterpolator()).start();
                if (focused) message.setText(label);
            }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose(name);
            }
        });
        grid.addView(cell);
        return cell;
    }

    /** thumbnails are decoded off the main thread, small, one at a time */
    private void loadThumbnails() {
        final int w = getResources().getDimensionPixelSize(R.dimen.bg_thumb_w);
        final int h = getResources().getDimensionPixelSize(R.dimen.bg_thumb_h);
        final List<View> cells = new ArrayList<>();
        for (int i = 0; i < grid.getChildCount(); i++) cells.add(grid.getChildAt(i));
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (final View cell : cells) {
                    final Bitmap bmp = decode((String) cell.getTag(), w, h, false);
                    if (bmp == null) continue;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ((ImageView) cell.findViewById(R.id.thumb_image)).setImageBitmap(bmp);
                        }
                    });
                }
            }
        }, "bg-thumbs").start();
    }

    private void choose(final String name) {
        if (busy) return;
        if (name == null) {
            HomeSettings.clearBackground(this);
            finish();
            return;
        }
        busy = true;
        message.setText(R.string.bg_applying);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        final int w = Math.max(dm.widthPixels, dm.heightPixels);
        final int h = Math.min(dm.widthPixels, dm.heightPixels);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = saveBackground(name, w, h);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (ok) {
                            finish();
                        } else {
                            message.setText(R.string.bg_failed);
                            Toast.makeText(BackgroundActivity.this, R.string.bg_failed, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "bg-apply").start();
    }

    private boolean saveBackground(String name, int w, int h) {
        Bitmap full = decode(name, w, h, true);
        if (full == null) return false;
        File out = HomeSettings.backgroundFile(this);
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            if (!full.compress(Bitmap.CompressFormat.JPEG, 90, fos)) return false;
            fos.getFD().sync();
            fos.close();
            fos = null;
            if (!tmp.renameTo(out)) return false;
            HomeSettings.setBackgroundSource(this, name);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            full.recycle();
        }
    }

    /**
     * Decodes a photo (or, for null, the bundled background) and centre-crops it to
     * exactly w x h, honouring the phone's EXIF rotation. inSampleSize keeps the decode
     * near the target size, so even a 48 MP photo never needs 48 MP of memory.
     */
    private Bitmap decode(String name, int w, int h, boolean exact) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            openInto(name, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int rotation = name == null ? 0 : exifRotation(name);
            boolean sideways = rotation == 90 || rotation == 270;
            int srcW = sideways ? bounds.outHeight : bounds.outWidth;
            int srcH = sideways ? bounds.outWidth : bounds.outHeight;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (srcW / (opts.inSampleSize * 2) >= w && srcH / (opts.inSampleSize * 2) >= h) {
                opts.inSampleSize *= 2;
            }
            if (!exact) opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap raw = openInto(name, opts);
            if (raw == null) return null;

            Bitmap out = Bitmap.createBitmap(w, h, exact ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(out);
            Matrix m = new Matrix();
            // rotate about the origin, then move the rotated image back into positive space
            m.postRotate(rotation);
            if (rotation == 90) m.postTranslate(raw.getHeight(), 0);
            else if (rotation == 180) m.postTranslate(raw.getWidth(), raw.getHeight());
            else if (rotation == 270) m.postTranslate(0, raw.getWidth());
            float rw = sideways ? raw.getHeight() : raw.getWidth();
            float rh = sideways ? raw.getWidth() : raw.getHeight();
            float scale = Math.max(w / rw, h / rh);
            m.postScale(scale, scale);
            m.postTranslate((w - rw * scale) / 2f, (h - rh * scale) / 2f);
            canvas.drawBitmap(raw, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
            raw.recycle();
            return out;
        } catch (OutOfMemoryError | Exception e) {
            return null;
        }
    }

    private Bitmap openInto(String name, BitmapFactory.Options opts) throws Exception {
        if (name == null) {
            return BitmapFactory.decodeResource(getResources(), R.drawable.kutu_home_background, opts);
        }
        InputStream in = getContentResolver().openInputStream(Uri.withAppendedPath(PROVIDER, Uri.encode(name)));
        if (in == null) return null;
        try {
            return BitmapFactory.decodeStream(in, null, opts);
        } finally {
            in.close();
        }
    }

    private int exifRotation(String name) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(Uri.withAppendedPath(PROVIDER, Uri.encode(name)));
            if (in == null) return 0;
            int o = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
            return 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
