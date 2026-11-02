package dev.iconmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.net.Uri;
import java.io.IOException;

final class IconBackground {
    static Bitmap read(Context context, Uri uri) throws IOException {
        return IconOverlay.readLayer(context, uri, R.string.background_invalid);
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
            Bitmap scaled = IconOverlay.scaled(bitmap, scale, R.string.background_scale_invalid);
            bitmap.recycle();
            return scaled;
        } catch (IOException | RuntimeException error) { bitmap.recycle(); throw error; }
    }

    static void validate(Bitmap bitmap) throws IOException {
        IconOverlay.validateRendered(bitmap, R.string.background_invalid, R.string.background_clipped_empty);
    }

    static Bitmap render(Bitmap background, IconShape shape, int[] corners, int visibleSize, boolean clip) {
        return render(background, shape, corners, visibleSize, clip, null);
    }

    static Bitmap render(Bitmap background, IconShape shape, int[] corners, int visibleSize, boolean clip, Bitmap fileMask) {
        return IconOverlay.border(background, shape, corners, visibleSize, clip, fileMask);
    }

    static void draw(Bitmap icon, Bitmap background) {
        if (background == null) return;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));
        new Canvas(icon).drawBitmap(background, null, new Rect(0, 0, icon.getWidth(), icon.getHeight()), paint);
    }
}
