package io.freemytv;

import java.io.IOException;

/** Pixel budgets checked before allocating a photo bitmap. No Android dependencies. */
final class PhotoDecode {
    private PhotoDecode() {}

    // At most ~46 MiB of ARGB pixels; EXIF rotation may temporarily need a second bitmap.
    private static final long MAX_PIXELS = 12_000_000L;
    private static final int MAX_SIDE = 8192;
    private static final int HOME_W = 1920, HOME_H = 1080;

    static void checkSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0) throw new IOException("unreadable photo dimensions");
        if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
            throw new IOException("photo exceeds 12 megapixels or 8192 pixels per side; resize on the computer");
        }
    }

    /** Largest power of two retaining enough pixels for a Full HD center crop; never upscale. */
    static int homeSampleSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0) throw new IOException("unreadable photo dimensions");
        int sample = 1;
        while (width / (2L * sample) >= HOME_W && height / (2L * sample) >= HOME_H) sample *= 2;
        // Use ceiling dimensions: some decoders round the sampled size upwards.
        checkSize((int) (((long) width + sample - 1) / sample),
                (int) (((long) height + sample - 1) / sample));
        return sample;
    }
}
