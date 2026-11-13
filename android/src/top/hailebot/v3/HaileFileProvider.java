package top.hailebot.v3;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class HaileFileProvider extends ContentProvider {
    public static final String AUTHORITY = "top.hailebot.v3.fileprovider";
    private static final String MIME_APK = "application/vnd.android.package-archive";

    public static Uri uriForOtaFile(Context context, File file) {
        return new Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath("ota")
            .appendPath(file.getName())
            .build();
    }

    private File resolveOtaFile(Uri uri) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null || uri == null) {
            throw new FileNotFoundException("Context or URI missing");
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("Invalid OTA filename");
        }
        File otaDir = new File(ctx.getCacheDir(), "ota");
        File target = new File(otaDir, name);
        try {
            String dirCanon = otaDir.getCanonicalPath();
            String fileCanon = target.getCanonicalPath();
            if (!fileCanon.startsWith(dirCanon + File.separator)) {
                throw new SecurityException("Path traversal blocked");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Canonicalization failed");
        }
        if (!target.exists() || !target.isFile()) {
            throw new FileNotFoundException("OTA APK not found");
        }
        return target;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        final File file;
        try {
            file = resolveOtaFile(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null && projection.length > 0
            ? projection
            : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        String[] cleanCols = new String[cols.length];
        Object[] values = new Object[cols.length];
        int i = 0;
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                cleanCols[i] = OpenableColumns.DISPLAY_NAME;
                values[i] = file.getName();
                i++;
            } else if (OpenableColumns.SIZE.equals(col)) {
                cleanCols[i] = OpenableColumns.SIZE;
                values[i] = file.length();
                i++;
            }
        }
        String[] finalCols = new String[i];
        Object[] finalValues = new Object[i];
        System.arraycopy(cleanCols, 0, finalCols, 0, i);
        System.arraycopy(values, 0, finalValues, 0, i);
        MatrixCursor cursor = new MatrixCursor(finalCols, 1);
        cursor.addRow(finalValues);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_APK;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.equals("r")) {
            throw new SecurityException("Read-only provider");
        }
        File file = resolveOtaFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("No external inserts");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No external deletes");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No external updates");
    }
}
