package dev.iconmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.net.Uri;
import java.io.IOException;

final class IconMask {
    static Bitmap read(Context context, Uri uri) throws IOException {
        return IconOverlay.readLayer(context, uri, R.string.mask_file_invalid);
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
        } catch (IOException | RuntimeException error) {
            bitmap.recycle();
            throw error;
        }
    }

    static Bitmap scaled(Bitmap mask, int scale) {
        return IconOverlay.scaled(mask, scale, R.string.mask_scale_invalid);
    }

    static void validate(Bitmap bitmap) throws IOException {
        IconOverlay.validateRendered(bitmap, R.string.mask_file_invalid, R.string.mask_file_empty);
    }

    static Bitmap render(Bitmap source, int visibleSize) {
        return render(source, visibleSize, IconShape.SIZE);
    }

    static Bitmap render(Bitmap source, int visibleSize, int canvasSize) {
        if (source == null) throw new IllegalArgumentException(App.text(R.string.mask_file_missing));
        if (source.getWidth() < 1 || source.getHeight() != source.getWidth()) {
            throw new IllegalArgumentException(App.text(R.string.mask_file_invalid));
        }
        Bitmap result = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888);
        try {
            float extent = visibleSize * (float) canvasSize / IconShape.SIZE;
            float inset = (canvasSize - extent) / 2f;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN));
            new Canvas(result).drawBitmap(source, null,
                    new RectF(inset, inset, inset + extent, inset + extent), paint);
            IconOverlay.validateRendered(result, R.string.mask_file_invalid, R.string.mask_file_empty);
            return result;
        } catch (IOException | RuntimeException error) {
            result.recycle();
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }
}
