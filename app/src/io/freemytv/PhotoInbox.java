package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Where tools/photos.py writes a photo when the TV doesn't let adb into the app's external folder (Android 11, and
 * Fire OS 8 on it): `adb exec-in content write --uri content://io.freemytv.photos/<name>?size=<bytes>`. Only adb's
 * shell (or root) may write; the photo lands in Photos' private inbox and is imported (and deleted) the next time the
 * home screen, the screensaver or the settings open.
 */
public class PhotoInbox extends ContentProvider {
    /** adb's shell (Process.SHELL_UID, which the SDK only names from Android 10). */
    private static final int SHELL_UID = 2000;

    @Override public boolean onCreate() {
        return true;
    }

    /** What tools/photos.py says it sends (?size=), or -1 (then any clean close keeps it). */
    private static long size(Uri uri) {
        try {
            return Long.parseLong(uri.getQueryParameter("size"));
        } catch (NumberFormatException e) {   // also when there's none
            return -1;
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb sends photos");
        String name = uri.getLastPathSegment();
        if ("backup.zip".equals(name) && "r".equals(mode)) {
            try {
                File backup = PhotoCollection.backup(getContext());
                ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(backup, ParcelFileDescriptor.MODE_READ_ONLY);
                backup.delete(); // The open descriptor stays readable; no exported ZIP is left on the TV.
                return descriptor;
            } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        }
        if (name == null || name.isEmpty() || name.contains("/") || name.startsWith(".") || !mode.contains("w")) {
            throw new FileNotFoundException("photos only: content://io.freemytv.photos/<name>");
        }
        boolean collection = "collection.zip".equals(name);
        File in = collection ? getContext().getFilesDir() : Photos.privateInbox(getContext());
        if (!in.isDirectory() && !in.mkdirs()) throw new FileNotFoundException("can't create " + in);
        long expected = size(uri);
        // Written under a hidden name (an import meanwhile leaves it alone) and named only once all of it came: a send
        // cut short (Ctrl-C, Wi-Fi) also closes cleanly. Imported like a pushed photo, at the next open: an import here,
        // in the main process, could save its stale copy of the settings over :home's (Prefs.setPhotos).
        File done = collection ? PhotoCollection.transfer(getContext()) : new File(in, name);
        File part = new File(in, "." + done.getName());
        if (collection && done.exists() && !done.delete()) throw new FileNotFoundException("cannot clear old transfer");
        try {
            return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                        boolean whole = e == null && (expected < 0 || part.length() == expected);
                        if (!whole) AppLog.w("photos: incomplete transfer " + part.length() + "/" + expected + ": " + e);
                        if (!whole || !part.renameTo(done)) part.delete();
                    });
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb manages photos");
        Bundle result = new Bundle();
        try {
            if ("collection-status".equals(method)) {
                result.putBoolean("ready", PhotoCollection.transfer(getContext()).isFile());
            } else if ("replace-collection".equals(method) || "restore-collection".equals(method)) {
                int count = PhotoCollection.replace(getContext(), "restore-collection".equals(method));
                result.putInt("photos", count);
                result.putBoolean("success", true);
            } else return super.call(method, arg, extras);
        } catch (IOException | RuntimeException e) {
            result.putString("error", e.getMessage());
        }
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
