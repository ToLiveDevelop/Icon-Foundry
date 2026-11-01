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
        if (reference.isEmpty()) return null;
        Bitmap bitmap = FileIcons.render(FileIcons.directory(context), reference, IconShape.SIZE);
        try {
            validate(bitmap);
            return bitmap;
        } catch (IOException | RuntimeException error) {
            bitmap.recycle();
            throw error;
        }
    }

    static void validate(Bitmap bitmap) throws IOException {
        if (bitmap.getWidth() != IconShape.SIZE || bitmap.getHeight() != IconShape.SIZE) {
            throw new IOException(App.text(R.string.mask_file_invalid));
        }
        IconOverlay.validateRendered(bitmap, R.string.mask_file_invalid, R.string.mask_file_empty);
    }

    static Bitmap render(Bitmap source, int visibleSize) {
        if (source == null) throw new IllegalArgumentException(App.text(R.string.mask_file_missing));
        if (source.getWidth() != IconShape.SIZE || source.getHeight() != IconShape.SIZE) {
            throw new IllegalArgumentException(App.text(R.string.mask_file_invalid));
        }
        Bitmap result = Bitmap.createBitmap(IconShape.SIZE, IconShape.SIZE, Bitmap.Config.ARGB_8888);
        try {
            float inset = (IconShape.SIZE - visibleSize) / 2f;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN));
            new Canvas(result).drawBitmap(source, null,
                    new RectF(inset, inset, inset + visibleSize, inset + visibleSize), paint);
            IconOverlay.validateRendered(result, R.string.mask_file_invalid, R.string.mask_file_empty);
            return result;
        } catch (IOException | RuntimeException error) {
            result.recycle();
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }
}
