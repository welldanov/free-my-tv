package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your own photos. A computer puts them in the app's external files folder (tools/photos.py: adb push to
 * Android/data/io.freemytv/files/photos, no permission needed) or, where adb can't reach it, through PhotoInbox; the
 * next time the home screen, the screensaver or the settings open, each one is imported once into the app's private
 * folder, turned upright (EXIF) and saved losslessly at its original size (which also drops its GPS and
 * other tags), and the original is deleted. While they are on (Home Settings > My Photos), the screensaver shows them
 * instead of the picks' photos: the same daily photo as the home screen's background without picks (no TMDB key),
 * a new one every local calendar day.
 */
final class Photos {
    private Photos() {}

    /** The screensaver's photo size (DreamView loads them 10% larger than the screen, for the slow zoom). */
    static final int W = 2112, H = 1188;
    /** At most this many; later ones wait in the folder until some are removed. */
    static final int MAX = 100;
    /** Smaller ones are icons and thumbnails, not photos. */
    private static final int MIN_SIDE = 640;

    static File dir(Context c) {
        return new File(c.getFilesDir(), "photos");
    }

    /** Where tools/photos.py puts them (the app's own external folder: adb can write there, and no permission). */
    private static File inbox(Context c) {
        return c.getExternalFilesDir("photos");
    }

    /**
     * Where PhotoInbox puts what adb writes when it can't reach the external folder: Android 11 keeps adb out of other
     * apps' Android/data folders (on Fire OS 8 too).
     */
    static File privateInbox(Context c) {
        return new File(c.getFilesDir(), "inbox");
    }

    /**
     * What's waiting in both inboxes, in name order: claims (another process importing) included, other hidden names
     * (a photo PhotoInbox is still writing) left out.
     */
    private static File[] sent(Context c) {
        List<File> out = new ArrayList<>();
        for (File in : new File[] {inbox(c), privateInbox(c)}) {
            File[] all = in == null ? null : in.listFiles();
            if (all != null) for (File f : all) if (!f.getName().startsWith(".") || f.getName().startsWith(CLAIM)) out.add(f);
        }
        File[] sorted = out.toArray(new File[0]);
        Arrays.sort(sorted, (a, b) -> original(a.getName()).compareTo(original(b.getName())));
        return sorted;
    }

    // MARK: - The imported photos

    private static long listedStamp = -1;
    private static List<File> listed = new ArrayList<>();

    /** The imported photos, in the order they came (one stat of the folder while nothing changed). */
    static synchronized List<File> files(Context c) {
        PhotoCollection.recover(c);
        File d = dir(c);
        long stamp = d.exists() ? d.lastModified() : 0;
        if (stamp != listedStamp) {
            List<File> out = new ArrayList<>();
            File[] all = d.listFiles();
            if (all != null) for (File f : all) if ((f.getName().endsWith(".jpg") || f.getName().endsWith(".png"))) out.add(f);
            Collections.sort(out);
            listed = out;
            listedStamp = stamp;
        }
        return listed;
    }

    /** On in Home Settings and something imported. */
    static boolean on(Context c) {
        return Prefs.photos() && !files(c).isEmpty();
    }

    /** Shared daily selection for Home and the screensaver. Call only with a nonempty collection. */
    private static File today(Context c) {
        List<File> list = files(c);
        return list.get(PhotoCollection.index(c, list.size()));
    }

    /** For the screensaver: only today's photo (no title, no caption); keep its collection index as the key. */
    static List<Recs.Pick> picks(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        if (!on(c)) return out;
        File photo = today(c);
        Recs.Pick p = new Recs.Pick();
        p.kind = "photo";
        p.id = files(c).indexOf(photo);
        p.photo = photo;
        out.add(p);
        return out;
    }

    // MARK: - The home screen's background

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static File built, building;
    private static Backdrop backdrop;

    /**
     * Today's photo, ready to draw as the home screen's background, or null (off, or the first one still being
     * built). When the date changes, the previous one stays until the new one is ready; then `ready` runs.
     */
    static synchronized Backdrop backdrop(Context c, Runnable ready) {
        if (!on(c)) {
            backdrop = null;
            built = null;
            return null;
        }
        File want = today(c);
        if (!want.equals(built) && !want.equals(building)) {
            building = want;
            worker.execute(() -> {
                Backdrop b = null;
                try {
                    Bitmap src = decodeHome(want);
                    if (src != null) b = Backdrop.make(src, true, true);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    AppLog.w("photos: background from " + want.getName() + ": " + e.getMessage());
                }
                synchronized (Photos.class) {
                    building = null;
                    if (b == null) return;
                    backdrop = b;
                    built = want;
                }
                main.post(ready);
            });
        }
        return backdrop;
    }

    // MARK: - Importing what the computer sent

    private static boolean importing;
    private static final List<Runnable> waiting = new ArrayList<>();

    /**
     * Imports what tools/photos.py put in the inbox, in the background, one photo at a time; `done` runs (on the main
     * thread) when something was imported, also when an import already under way (asked by another screen) brings it.
     * Cheap when the inbox is empty: one directory listing.
     */
    static void importInbox(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        synchronized (Photos.class) {
            if (done != null) waiting.add(done);
            if (importing) return;
            importing = true;
        }
        worker.execute(() -> {
            int n = 0;
            try {
                n = importNow(app);
            } catch (RuntimeException e) {
                AppLog.w("photos: import failed: " + e.getMessage());
            }
            List<Runnable> tell;
            synchronized (Photos.class) {
                importing = false;
                tell = new ArrayList<>(waiting);
                waiting.clear();
            }
            if (n > 0) main.post(() -> { for (Runnable r : tell) r.run(); });
        });
    }

    /** A photo one process is importing is named ".claim-<when>-<its name>". */
    private static final String CLAIM = ".claim-";
    /** A claim this old was left by a process that died while importing: another one may take the photo. */
    private static final long CLAIM_MS = 10 * 60_000L;

    private static String original(String name) {
        if (!name.startsWith(CLAIM)) return name;
        int dash = name.indexOf('-', CLAIM.length());
        return dash < 0 ? name : name.substring(dash + 1);
    }

    private static boolean abandoned(String claimed) {
        int dash = claimed.indexOf('-', CLAIM.length());
        if (dash < 0) return false;
        try {
            long when = Long.parseLong(claimed.substring(CLAIM.length(), dash));
            return Math.abs(System.currentTimeMillis() - when) > CLAIM_MS;   // also one dated ahead: the clock went back
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static int importNow(Context c) {
        File[] all = sent(c);
        if (all.length == 0) return 0;
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) {
            AppLog.w("photos: can't create " + d);
            return 0;
        }
        // Counted on disk, not from files(): its cache is keyed on the folder's time, which another process's import
        // (or two within the same second) may not move.
        File[] imported = d.listFiles((dir, name) -> name.endsWith(".jpg") || name.endsWith(".png"));
        int have = imported == null ? 0 : imported.length, count = 0;
        for (File sent : all) {
            if (!sent.isFile()) continue;
            String name = sent.getName();
            if (name.startsWith(CLAIM) && !abandoned(name)) continue;   // another process is importing it
            String original = original(name);
            if (!image(original.toLowerCase(Locale.ROOT))) {
                sent.delete();
                continue;
            }
            if (have + count >= MAX) {
                AppLog.w("photos: " + MAX + " already, " + original + " waits");
                continue;
            }
            // Claimed with a rename before it's read: on Android 11 the screensaver (main process) and the home screen
            // (:home) both import, and without it they could take the same photo twice.
            File src = new File(sent.getParentFile(), CLAIM + System.currentTimeMillis() + "-" + original);
            if (!sent.renameTo(src)) continue;
            // Named by import time (the order they came is the order the screensaver shows them) and by process (the
            // other one may import in the same millisecond); written as .part first, so a crash leaves no half photo.
            File out = new File(d, String.format(Locale.US, "%013d-%d.png", System.currentTimeMillis() + count,
                    android.os.Process.myPid()));
            File part = new File(d, out.getName() + ".part");
            try {
                Bitmap b = decode(src);
                if (b == null) {
                    AppLog.w("photos: skipped " + original + " (too small or unreadable)");
                } else {
                    try (OutputStream os = new FileOutputStream(part)) {
                        b.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    b.recycle();
                    if (!part.renameTo(out)) throw new IOException("can't name " + out.getName());
                    count++;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                part.delete();
                AppLog.w("photos: skipped " + original + ": " + e.getMessage());
            }
            src.delete();
        }
        AppLog.w("photos: imported " + count + ", " + (have + count) + " in all");
        if (count > 0) Prefs.setPhotos(true);   // new photos show, even if they had been turned off
        return count;
    }

    /** Deletes the imported photos and whatever is still waiting, half-sent ones included. */
    static void removeAll(Context c) {
        try {
            PhotoCollection.forgetPrevious(c);
        } catch (IOException e) {
            AppLog.w("photos: cannot remove previous collection: " + e.getMessage());
            return;
        }
        clear(dir(c));
        clear(privateInbox(c));
        File in = inbox(c);
        File[] queued = in == null ? null : in.listFiles();
        if (queued != null) for (File f : queued) f.delete();
        synchronized (Photos.class) {
            backdrop = null;
            built = null;
        }
    }

    private static boolean image(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
                || (Build.VERSION.SDK_INT >= 28 && (name.endsWith(".heic") || name.endsWith(".heif")));
    }

    /** Sample before allocating pixels; imported photos are already upright. */
    private static Bitmap decodeHome(File f) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = PhotoDecode.homeSampleSize(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeFile(f.getPath(), options);
    }

    /** Preserve accepted source pixels and dimensions; reject oversized originals before allocation. */
    static Bitmap decode(File f) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (Math.min(bounds.outWidth, bounds.outHeight) < MIN_SIDE) return null;
        PhotoDecode.checkSize(bounds.outWidth, bounds.outHeight);
        Bitmap src = BitmapFactory.decodeFile(f.getPath());
        if (src == null) return null;
        int degrees = rotation(f);
        if (degrees == 0) return src;
        Matrix m = new Matrix();
        m.setRotate(degrees);
        Bitmap turned = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (turned != src) src.recycle();
        return turned;
    }

    private static int rotation(File f) {
        try {
            switch (new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static void clear(File d) {
        File[] all = d.listFiles();
        if (all != null) for (File f : all) f.delete();
        d.delete();
    }
}
