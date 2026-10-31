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
        Bitmap bitmap = android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(context.getContentResolver(), uri), (decoder, info, source) -> {
                    if (!"image/png".equals(info.getMimeType()) || info.getSize().getWidth() != info.getSize().getHeight()) {
                        throw new IllegalArgumentException(context.getString(R.string.overlay_invalid));
                    }
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setTargetSize(IconShape.SIZE, IconShape.SIZE);
                });
        try {
            validate(bitmap);
            return bitmap;
        } catch (IOException error) { bitmap.recycle(); throw error; }
    }

    static void validate(Bitmap bitmap) throws IOException {
        if (bitmap.getWidth() != IconShape.SIZE || bitmap.getHeight() != IconShape.SIZE) {
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
        if (reference.isEmpty()) return null;
        Bitmap bitmap = FileIcons.render(FileIcons.directory(context), reference, IconShape.SIZE);
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
        if (scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException(App.text(R.string.overlay_scale_invalid));
        }
        Bitmap result = Bitmap.createBitmap(IconShape.SIZE, IconShape.SIZE, Bitmap.Config.ARGB_8888);
        float size = IconShape.SIZE * scale / 100f;
        float inset = (IconShape.SIZE - size) / 2;
        new Canvas(result).drawBitmap(overlay, null,
                new android.graphics.RectF(inset, inset, inset + size, inset + size),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    static void draw(Bitmap icon, Bitmap overlay) {
        if (overlay == null) return;
        new Canvas(icon).drawBitmap(overlay, null, new Rect(0, 0, icon.getWidth(), icon.getHeight()),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
    }
}
