package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.zip.*;

/** ADB collection replacement. Validate everything before publishing; keep the previous collection for recovery. */
final class PhotoCollection {
    private static final String START = ".cycle-start";
    private static final long LIMIT = 512L * 1024 * 1024;
    private static long startStamp = Long.MIN_VALUE;
    private static LocalDate cachedStart;

    static File transfer(Context c) { return new File(c.getFilesDir(), "photo-collection.zip"); }

    static synchronized int index(Context c, int count) {
        File marker = new File(Photos.dir(c), START);
        LocalDate today = LocalDate.now();
        long stamp = Photos.dir(c).lastModified();
        if (cachedStart == null || stamp != startStamp) {
            try {
                cachedStart = LocalDate.parse(new String(java.nio.file.Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim());
            } catch (IOException | RuntimeException e) {
                cachedStart = today;
                try { writeDate(marker, cachedStart); } catch (IOException ignored) {}
            }
            startStamp = Photos.dir(c).lastModified();
        }
        return dayIndex(cachedStart, today, count);
    }

    static int dayIndex(LocalDate start, LocalDate today, int count) {
        return (int) Math.floorMod(today.toEpochDay() - start.toEpochDay(), (long) count);
    }

    private static void writeDate(File f, LocalDate date) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(date.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static File backup(Context c) throws IOException {
        File zip = new File(c.getFilesDir(), "photo-collection-backup.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            File[] files = Photos.dir(c).listFiles();
            if (files != null) {
                java.util.Arrays.sort(files);
                for (File f : files) {
                    if (!f.isFile()) continue;
                    out.putNextEntry(new ZipEntry(f.getName()));
                    java.nio.file.Files.copy(f.toPath(), out);
                    out.closeEntry();
                }
            }
        }
        return zip;
    }

    /** Also called before listing photos: recover a process death between the two directory renames. */
    static void recover(Context c) {
        File active = Photos.dir(c), previous = new File(c.getFilesDir(), "photos-previous");
        if (!active.exists() && previous.isDirectory()) previous.renameTo(active);
    }

    static void forgetPrevious(Context c) throws IOException {
        clear(new File(c.getFilesDir(), "photos-previous"));
        clear(new File(c.getFilesDir(), "photos-staging"));
        transfer(c).delete();
        new File(c.getFilesDir(), "photo-collection-backup.zip").delete();
    }

    static synchronized int replace(Context c, boolean restore) throws IOException {
        recover(c);
        File active = Photos.dir(c), staging = new File(c.getFilesDir(), "photos-staging");
        File previous = new File(c.getFilesDir(), "photos-previous");
        clear(staging);
        if (!staging.mkdir()) throw new IOException("cannot create staging folder");
        int count = 0;
        long total = 0;
        LocalDate start = LocalDate.now();
        String generation = Long.toString(System.currentTimeMillis());
        byte[] buffer = new byte[32768];
        try {
            try (ZipInputStream zip = new ZipInputStream(new FileInputStream(transfer(c)))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || name.contains("/") || name.contains("\\")) throw new IOException("flat image ZIP required");
                    if (START.equals(name) && restore) {
                        byte[] date = new byte[10];
                        new DataInputStream(zip).readFully(date);
                        if (zip.read() != -1) throw new IOException("invalid cycle date");
                        start = LocalDate.parse(new String(date, StandardCharsets.UTF_8));
                        continue;
                    }
                    if (++count > Photos.MAX) throw new IOException("too many photos");
                    File source = new File(staging, ".source");
                    try (FileOutputStream out = new FileOutputStream(source)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            total += n;
                            if (total > LIMIT) throw new IOException("collection exceeds 512 MiB");
                            out.write(buffer, 0, n);
                        }
                    }
                    Bitmap bitmap = Photos.decode(source);
                    if (bitmap == null) throw new IOException("unreadable or too small: " + name);
                    File target = new File(staging, String.format(java.util.Locale.US, "%s-%03d.png", generation, count));
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encoding failed");
                    } finally { bitmap.recycle(); }
                    if (!source.delete()) throw new IOException("cannot remove staging source");
                }
            }
            if (count == 0) throw new IOException("empty collection");
            writeDate(new File(staging, START), start);
            // Ensure other processes' directory caches see the replacement even on a coarse filesystem clock.
            staging.setLastModified(Math.max(System.currentTimeMillis(), active.lastModified() + 2000));
            clear(previous);
            if (active.exists() && !active.renameTo(previous)) throw new IOException("cannot preserve previous collection");
            if (!staging.renameTo(active)) {
                previous.renameTo(active);
                throw new IOException("cannot publish collection");
            }
            transfer(c).delete();
            Prefs.setPhotos(true);
            return count;
        } catch (RuntimeException | OutOfMemoryError e) {
            throw new IOException("invalid collection: " + e.getMessage(), e);
        } finally { clear(staging); }
    }

    private static void clear(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (!f.isFile() || !f.delete()) throw new IOException("cannot clear " + f.getName());
        }
        if (dir.exists() && !dir.delete()) throw new IOException("cannot clear " + dir.getName());
    }
}
