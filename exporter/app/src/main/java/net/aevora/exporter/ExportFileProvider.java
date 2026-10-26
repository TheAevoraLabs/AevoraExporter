package net.aevora.exporter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal file provider, no androidx dependency.
 *
 * Serves only files under getExternalFilesDir()/exported/, which is
 * where the exporter writes its output APKs. The installer app gets
 * read access via FLAG_GRANT_READ_URI_PERMISSION on the install intent.
 * Any path outside exported/ is refused.
 */
public class ExportFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File dir = new File(getContext().getExternalFilesDir(null), "exported");
        File file = new File(dir, uri.getLastPathSegment());
        if (!file.getPath().startsWith(dir.getPath() + File.separator)) {
            throw new FileNotFoundException("outside exported dir");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
