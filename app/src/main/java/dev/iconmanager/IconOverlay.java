package dev.iconmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import java.io.IOException;

final class IconOverlay {
    static final int MIN_SCALE = 50, MAX_SCALE = 150;

    static Bitmap read(Context context, Uri uri) throws IOException {
        Bitmap bitmap = readLayer(context, uri, R.string.overlay_invalid);
        try { validate(bitmap); return bitmap; }
        catch (IOException | RuntimeException error) { bitmap.recycle(); throw error; }
    }

    static Bitmap readLayer(Context context, Uri uri, int invalidMessage) throws IOException {
        Bitmap bitmap = android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(context.getContentResolver(), uri), (decoder, info, source) -> {
                    if (info.getSize().getWidth() > FileIcons.MAX_SIZE || info.getSize().getHeight() > FileIcons.MAX_SIZE) {
                        throw new IllegalArgumentException(context.getString(R.string.layer_size_invalid));
                    }
                    if (!"image/png".equals(info.getMimeType()) || info.getSize().getWidth() != info.getSize().getHeight()) {
                        throw new IllegalArgumentException(context.getString(invalidMessage));
                    }
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
        try {
            validateRendered(bitmap, invalidMessage, invalidMessage);
            return bitmap;
        } catch (IOException | RuntimeException error) { bitmap.recycle(); throw error; }
    }

    static void validate(Bitmap bitmap) throws IOException {
        if (bitmap.getWidth() < 1 || bitmap.getWidth() > FileIcons.MAX_SIZE || bitmap.getHeight() != bitmap.getWidth()) {
            throw new IOException(App.text(R.string.overlay_invalid));
        }
        boolean visible = false, transparent = false;
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            int alpha = android.graphics.Color.alpha(bitmap.getPixel(x, y));
            visible |= alpha > 0;
            transparent |= alpha == 0;
        }
        if (!visible || !transparent) throw new IOException(App.text(R.string.overlay_invalid));
    }

    static Bitmap load(Context context, String reference) throws IOException {
        return load(context, reference, 100);
    }

    static Bitmap load(Context context, String reference, int scale) throws IOException {
        return load(context, reference, scale, IconShape.SIZE);
    }

    static Bitmap load(Context context, String reference, int scale, int size) throws IOException {
        if (reference.isEmpty()) return null;
        Bitmap bitmap = FileIcons.render(FileIcons.directory(context), reference, size);
        try {
            validate(bitmap);
            if (scale == 100) return bitmap;
            Bitmap scaled = scaled(bitmap, scale);
            bitmap.recycle();
            return scaled;
        }
        catch (IOException | RuntimeException error) { bitmap.recycle(); throw error; }
    }

    static Bitmap scaled(Bitmap overlay, int scale) {
        return scaled(overlay, scale, R.string.overlay_scale_invalid);
    }

    static Bitmap scaled(Bitmap overlay, int scale, int invalidMessage) {
        if (scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException(App.text(invalidMessage));
        }
        int canvasSize = overlay.getWidth();
        Bitmap result = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888);
        float size = canvasSize * scale / 100f;
        float inset = (canvasSize - size) / 2;
        new Canvas(result).drawBitmap(overlay, null,
                new android.graphics.RectF(inset, inset, inset + size, inset + size),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    /** Scaled or clipped overlays may legitimately fill the whole canvas, so only size and visibility matter. */
    static void validateRendered(Bitmap bitmap) throws IOException {
        validateRendered(bitmap, R.string.overlay_invalid, R.string.overlay_clipped_empty);
    }

    static void validateRendered(Bitmap bitmap, int invalidMessage, int emptyMessage) throws IOException {
        if (bitmap.getWidth() < 1 || bitmap.getWidth() > FileIcons.MAX_SIZE || bitmap.getHeight() != bitmap.getWidth()) {
            throw new IOException(App.text(invalidMessage));
        }
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) return;
        }
        throw new IOException(App.text(emptyMessage));
    }

    /** Returns the native icon_border bitmap, optionally clipped to the fallback mask silhouette. */
    static Bitmap border(Bitmap overlay, IconShape shape, int[] corners, int maskSize, boolean clip) {
        return border(overlay, shape, corners, maskSize, clip, null);
    }

    static Bitmap border(Bitmap overlay, IconShape shape, int[] corners, int maskSize, boolean clip, Bitmap fileMask) {
        Bitmap result = overlay.copy(Bitmap.Config.ARGB_8888, true);
        if (!clip || shape == IconShape.NONE) return result;
        try {
            Bitmap mask = shape.mask(maskSize, corners, fileMask, overlay.getWidth());
            try { applyAlpha(result, mask); }
            finally { mask.recycle(); }
            return result;
        } catch (RuntimeException error) {
            result.recycle();
            throw error;
        }
    }

    static void draw(Bitmap icon, Bitmap overlay) {
        if (overlay == null) return;
        new Canvas(icon).drawBitmap(overlay, null, new Rect(0, 0, icon.getWidth(), icon.getHeight()),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
    }

    /** Draws the overlay, optionally keeping it inside the icon's own visible silhouette. */
    static void draw(Bitmap icon, Bitmap overlay, boolean clipToIcon) {
        if (overlay == null) return;
        if (!clipToIcon) { draw(icon, overlay); return; }
        Bitmap layer = Bitmap.createBitmap(icon.getWidth(), icon.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            draw(layer, overlay);
            applyAlpha(layer, icon);
            new Canvas(icon).drawBitmap(layer, 0, 0, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        } finally { layer.recycle(); }
    }

    private static void applyAlpha(Bitmap target, Bitmap alpha) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        new Canvas(target).drawBitmap(alpha, null, new Rect(0, 0, target.getWidth(), target.getHeight()), paint);
    }
}
