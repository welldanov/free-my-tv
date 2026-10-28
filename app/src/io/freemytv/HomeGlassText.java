package io.freemytv;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/** Content masks are built only on a content/size change; the Home hardware layer caches the shaded result. */
final class HomeGlassText {
    private static final String SHADER = Shaders.GLASS_TEXT
            .replace("uniform float alpha;", "uniform float alpha;\nuniform float density;\nuniform half3 inkColor;")
            .replace("half k = a * half(alpha);", "col = mix(col, inkColor, half(density));\n  half k = a * half(alpha);");
    private final Executor worker;
    private final Handler handler;
    private final Runnable changed;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private RuntimeShader shader;
    private Mask mask, recordedMask;
    private final Recording result = Recording.create("home-glass-text");
    private long backgroundVersion = -1;
    private float recordedX, recordedY, recordedDensity;
    private int recordedColor;
    private String requested = "";
    private String weatherText, weatherSymbol, weatherKey;
    private int generation;
    private float requestedX, requestedBaseline, requestedScale;

    HomeGlassText(Executor worker, Handler handler, Runnable changed) {
        this.worker = worker;
        this.handler = handler;
        this.changed = changed;
        try { shader = new RuntimeShader(SHADER); fill.setShader(shader); }
        catch (RuntimeException | LinkageError e) {
            AppLog.w("home glass shader unavailable: " + e.getMessage());
            shader = null;
        }
        shadowPaint.setColor(0x90000000);
        result.setUseCompositingLayer(true, null);
    }

    /** Android may discard nested layers when the parent is hidden; keep masks, re-record the small result. */
    void invalidateResult() { recordedMask = null; }

    void clear() {
        generation++;
        requested = "";
        mask = recordedMask = null;
    }

    void draw(Canvas c, Scene scene, Context context, String text, String symbol, Paint paint,
              float x, float baseline, float scale, float density, int inkColor) {
        // Paint metrics are stable until makePaints clears this cache. Text/symbol keys are compared without allocations.
        String key = text;
        if (symbol != null) {
            if (!text.equals(weatherText) || !symbol.equals(weatherSymbol)) {
                weatherText = text; weatherSymbol = symbol; weatherKey = symbol + "|" + text;
            }
            key = weatherKey;
        }
        final String contentKey = key;
        if (shader != null && (!key.equals(requested) || requestedX != x
                || requestedBaseline != baseline || requestedScale != scale)) {
            requested = key;
            requestedX = x; requestedBaseline = baseline; requestedScale = scale;
            final int gen = ++generation;
            // Only snapshot mutable UI state here; all measuring, rasterization and mask allocation run in the worker.
            final Paint snapshot = new Paint(paint);
            final Context appContext = context.getApplicationContext();
            worker.execute(() -> {
                Mask built;
                try { built = build(appContext, text, symbol, snapshot, x, baseline, scale, contentKey); }
                catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("home glass mask unavailable: " + e);
                    handler.post(() -> { if (gen == generation) { shader = null; mask = null; changed.run(); } });
                    return;
                }
                handler.post(() -> {
                    if (gen != generation) return;
                    mask = built;
                    shader.setInputShader("cover", nearest(built.cover));
                    shader.setInputShader("slope", nearest(built.slope));
                    changed.run();
                });
            });
        }
        Mask m = mask;
        // Like DreamView: retain the last ready glass while its replacement builds.
        // The UI callback swaps the mask and both shader inputs together, then invalidates once.
        if (shader == null || m == null || !scene.hasTextGlass()) {
            float size = 40 * scale;
            float sw = symbol == null ? 0 : Symbols.width(context, symbol, size);
            float gap = symbol == null ? 0 : sw + 16 * scale;
            float originX = symbol == null ? x - Ui.leftBearing(paint, text) : x;
            Paint readable = fillFallback(paint, inkColor, scale);
            c.drawText(text, originX + gap, baseline, readable);
            if (symbol != null) Symbols.draw(context, c, symbol, x + sw / 2, baseline - Ui.halfCap(paint), size, inkColor);
            return;
        }
        // Position belongs to the ready mask, never to the newer pending request.
        float left = m.left, top = m.top;
        float drawScale = m.scale;
        long version = scene.textGlassVersion();
        if (recordedMask == m && backgroundVersion == version && recordedX == left && recordedY == top
                && recordedDensity == density && recordedColor == inkColor) {
            result.draw(c);
            return;
        }
        scene.bindTextGlass(shader);
        int width = m.cover.getWidth(), height = m.cover.getHeight() + Math.max(1, Math.round(4 * drawScale));
        result.setPosition(0, 0, width, height);
        result.setTranslationX(left);
        result.setTranslationY(top);
        Canvas cached = result.beginRecording(width, height);
        cached.translate(-left, -top);
        // Keep the clock unchanged; a low-opacity, softly spread shadow lifts the small text and icons.
        shadowPaint.setColor(m.large ? 0x90000000 : 0x30000000);
        cached.drawBitmap(m.shadow, left, top + (m.large ? 3 : 1.5f) * drawScale, shadowPaint);
        shader.setFloatUniform("origin", left, top);
        shader.setFloatUniform("ink", m.pad, Math.max(1, m.cover.getHeight() - 2 * m.pad));
        shader.setFloatUniform("refraction", (m.large ? 12 : 0.65f) * drawScale);
        shader.setFloatUniform("specular", m.large ? 1 : 0.65f);
        shader.setFloatUniform("alpha", 1);
        shader.setFloatUniform("density", density);
        shader.setFloatUniform("inkColor", Color.red(inkColor) / 255f, Color.green(inkColor) / 255f, Color.blue(inkColor) / 255f);
        cached.drawRect(left, top, left + m.cover.getWidth(), top + m.cover.getHeight(), fill);
        result.endRecording();
        recordedMask = m;
        backgroundVersion = version;
        recordedX = left; recordedY = top; recordedDensity = density; recordedColor = inkColor;
        result.draw(c);
    }

    private final Paint fallback = new Paint();
    private Paint fillFallback(Paint source, int color, float scale) {
        fallback.set(source); fallback.setColor(color);
        boolean large = source.getTextSize() > 100 * scale;
        fallback.setShadowLayer((large ? 5 : 2.5f) * scale, 0, (large ? 2 : 1.5f) * scale, large ? 0x70000000 : 0x30000000);
        return fallback;
    }

    private static BitmapShader nearest(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Frost.filter(s, BitmapShader.FILTER_MODE_NEAREST);
        return s;
    }

    private static final class Mask {
        final Bitmap cover, slope, shadow;
        final float left, top, scale;
        final int pad;
        final boolean large;
        final String key;
        Mask(Bitmap cover, Bitmap slope, Bitmap shadow, float left, float top, int pad, float scale, boolean large, String key) {
            this.cover = cover; this.slope = slope; this.shadow = shadow;
            this.left = left; this.top = top; this.pad = pad; this.scale = scale; this.large = large; this.key = key;
        }
    }

    private static Mask build(Context context, String text, String symbol, Paint paint,
                              float anchorX, float baseline, float scale, String key) {
        float size = 40 * scale;
        float sw = symbol == null ? 0 : Symbols.width(context, symbol, size);
        float gap = symbol == null ? 0 : sw + 16 * scale;
        float originX = symbol == null ? anchorX - Ui.leftBearing(paint, text) : anchorX;
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        if (symbol != null) {
            ink.left = Math.min(0, Math.round(gap + ink.left));
            ink.right = Math.round(gap + ink.right);
            ink.top = Math.min(ink.top, (int) Math.floor(-Ui.halfCap(paint) - size / 2));
            ink.bottom = Math.max(ink.bottom, (int) Math.ceil(-Ui.halfCap(paint) + size / 2));
        }
        boolean large = paint.getTextSize() > 100 * scale;
        int pad = Math.max(4, Math.round((symbol == null && large ? 32 : 10) * scale));
        int w = Math.max(1, ink.width() + 2 * pad), h = Math.max(1, ink.height() + 2 * pad);
        Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas raster = new Canvas(cover);
        raster.translate(pad - ink.left, pad - ink.top);
        paint.clearShadowLayer(); paint.setColor(Color.WHITE);
        raster.drawText(text, gap, 0, paint);
        if (symbol != null) Symbols.draw(context, raster, symbol, sw / 2, -Ui.halfCap(paint), size, Color.WHITE);
        float left = originX + ink.left - pad, top = baseline + ink.top - pad;
        float radius = large ? 6 * scale : 1.2f * scale;
        int[] alpha = new int[w * h];
        cover.getPixels(alpha, 0, w, 0, 0, w, h);
        for (int i = 0; i < alpha.length; i++) alpha[i] = (alpha[i] >>> 24) * 16;
        int[] height = blur(alpha, w, h, Math.max(1, Math.round(radius)), 3);
        int[] soft = blur(alpha, w, h, Math.max(1, Math.round(large ? radius * 2 : 2 * scale)), 3);
        // Reuse alpha for the packed slope instead of another full-size int array.
        float max = 1;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int i = y * w + x;
            float gx = height[i + 1] - height[i - 1], gy = height[i + w] - height[i - w];
            max = Math.max(max, (float) Math.sqrt(gx * gx + gy * gy));
        }
        byte[] shadowBytes = new byte[cover.getRowBytes() * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x;
            float gx = x > 0 && x < w - 1 ? height[i + 1] - height[i - 1] : 0;
            float gy = y > 0 && y < h - 1 ? height[i + w] - height[i - w] : 0;
            int r = 128 + Math.round(127 * gx / max), g = 128 + Math.round(127 * gy / max);
            alpha[i] = 0xFF000000 | (r << 16) | (g << 8) | Math.min(255, height[i] / 16);
            shadowBytes[y * cover.getRowBytes() + x] = (byte) Math.min(255, soft[i] / 16);
        }
        Bitmap shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        shadow.copyPixelsFromBuffer(ByteBuffer.wrap(shadowBytes));
        return new Mask(cover, Bitmap.createBitmap(alpha, w, h, Bitmap.Config.ARGB_8888), shadow, left, top, pad, scale, large, key);
    }

    private static int[] blur(int[] src, int w, int h, int r, int passes) {
        int[] a = src.clone(), b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep, sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += in[base + i * step];
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (sum + div / 2) / div;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }

}
