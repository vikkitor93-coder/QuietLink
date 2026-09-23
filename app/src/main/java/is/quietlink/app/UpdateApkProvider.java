package is.quietlink.app;

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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class UpdateApkProvider extends ContentProvider {
    private static final String MIME_APK = "application/vnd.android.package-archive";
    private static final String MIME_TEXT = "text/plain";

    static Uri uriFor(Context context, File apk) throws Exception {
        File allowed = new File(context.getCacheDir(), "updates").getCanonicalFile();
        File candidate = apk.getCanonicalFile();
        String allowedPrefix = allowed.getPath() + File.separator;
        if (!candidate.getPath().startsWith(allowedPrefix) || !candidate.isFile()) {
            throw new SecurityException("Update file outside update cache");
        }
        String encoded = URLEncoder.encode(candidate.getName(),
                StandardCharsets.UTF_8.name()).replace("+", "%20");
        return Uri.parse("content://" + context.getPackageName()
                + ".updates/apk/" + encoded);
    }

    static Uri uriForDiagnosticLog(Context context, File log) throws Exception {
        File allowed = new File(context.getCacheDir(), "diagnostics").getCanonicalFile();
        File candidate = log.getCanonicalFile();
        String allowedPrefix = allowed.getPath() + File.separator;
        if (!candidate.getPath().startsWith(allowedPrefix)
                || !candidate.isFile()
                || !candidate.getName().endsWith(".txt")) {
            throw new SecurityException("Diagnostic file outside diagnostics cache");
        }
        String encoded = URLEncoder.encode(candidate.getName(),
                StandardCharsets.UTF_8.name()).replace("+", "%20");
        return Uri.parse("content://" + context.getPackageName()
                + ".updates/log/" + encoded);
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            if (uri == null || uri.getPathSegments().size() != 2) {
                throw new FileNotFoundException();
            }
            String kind = uri.getPathSegments().get(0);
            String name = URLDecoder.decode(uri.getPathSegments().get(1),
                    StandardCharsets.UTF_8.name());
            if (name.contains("/") || name.contains("\\")) {
                throw new FileNotFoundException();
            }

            File allowed;
            if ("apk".equals(kind) && name.endsWith(".apk")) {
                allowed = new File(getContext().getCacheDir(), "updates").getCanonicalFile();
            } else if ("log".equals(kind) && name.endsWith(".txt")) {
                allowed = new File(getContext().getCacheDir(), "diagnostics").getCanonicalFile();
            } else {
                throw new FileNotFoundException();
            }

            File candidate = new File(allowed, name).getCanonicalFile();
            if (!candidate.getPath().startsWith(allowed.getPath() + File.separator)
                    || !candidate.isFile()) {
                throw new FileNotFoundException();
            }
            return candidate;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException();
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        try {
            return uri != null
                    && uri.getPathSegments().size() == 2
                    && "log".equals(uri.getPathSegments().get(0))
                    ? MIME_TEXT : MIME_APK;
        } catch (Exception ignored) {
            return MIME_APK;
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(resolve(uri),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            String[] cols = projection == null
                    ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                    : projection;
            MatrixCursor c = new MatrixCursor(cols, 1);
            MatrixCursor.RowBuilder row = c.newRow();
            for (String col : cols) {
                if (OpenableColumns.DISPLAY_NAME.equals(col)) row.add(f.getName());
                else if (OpenableColumns.SIZE.equals(col)) row.add(f.length());
                else row.add(null);
            }
            return c;
        } catch (Exception e) {
            return new MatrixCursor(projection == null
                    ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                    : projection, 0);
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override public int update(Uri uri, ContentValues values,
                                String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
