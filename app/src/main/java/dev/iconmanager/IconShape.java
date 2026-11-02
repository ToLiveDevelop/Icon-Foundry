package dev.iconmanager;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

enum IconShape {
    NONE(R.string.shape_none),
    CIRCLE(R.string.shape_circle),
    ROUNDED_SQUARE(R.string.shape_rounded_square),
    SQUIRCLE(R.string.shape_squircle),
    SQUARE(R.string.shape_square),
    TEARDROP(R.string.shape_teardrop),
    HEART(R.string.shape_heart),
    STAR(R.string.shape_star),
    HEXAGON(R.string.shape_hexagon),
    PENTAGON(R.string.shape_pentagon),
    CUSTOM(R.string.shape_custom),
    FILE(R.string.shape_file);

    final int label;
    static final int SIZE = 192;
    static final int INSET = 10;
    static final int MAX_CORNER = 100;
    /** Corner radii in percent of half the side: top-left, top-right, bottom-right, bottom-left. */
    static final int[] DEFAULT_CORNERS = {30, 30, 30, 30};
    private static final float ROUND_RADIUS = .22f;

    IconShape(int label) { this.label = label; }

    static Bitmap previewSource(Drawable drawable) { return previewSource(drawable, true); }

    static Bitmap previewSource(Drawable drawable, boolean unmask) {
        Bitmap source = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source);
        if (unmask && drawable instanceof AdaptiveIconDrawable adaptive) {
            int inset = Math.round(SIZE * AdaptiveIconDrawable.getExtraInsetFraction());
            for (Drawable layer : new Drawable[]{adaptive.getBackground(), adaptive.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(-inset, -inset, SIZE + inset, SIZE + inset);
                layer.draw(canvas);
            }
        } else {
            drawable.setBounds(0, 0, SIZE, SIZE);
            drawable.draw(canvas);
        }
        return source;
    }

    Bitmap preview(Bitmap source) { return preview(source, SIZE - 2 * INSET); }

    Bitmap preview(Drawable drawable, int visibleSize) { return preview(drawable, visibleSize, null); }

    Bitmap preview(Drawable drawable, int visibleSize, int[] corners) {
        return preview(drawable, visibleSize, corners, null);
    }

    Bitmap preview(Drawable drawable, int visibleSize, int[] corners, Bitmap fileMask) {
        Bitmap source = previewSource(drawable, this != NONE);
        try { return preview(source, visibleSize, corners, fileMask); }
        finally { source.recycle(); }
    }

    Bitmap preview(Bitmap source, int visibleSize) { return preview(source, visibleSize, null); }

    Bitmap preview(Bitmap source, int visibleSize, int[] corners) {
        return preview(source, visibleSize, corners, null);
    }

    Bitmap preview(Bitmap source, int visibleSize, int[] corners, Bitmap fileMask) {
        Bitmap result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(result);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            int left = source.getWidth(), top = source.getHeight(), right = -1, bottom = -1;
            for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
                if (Color.alpha(source.getPixel(x, y)) > 0) {
                    left = Math.min(left, x); top = Math.min(top, y);
                    right = Math.max(right, x); bottom = Math.max(bottom, y);
                }
            }
            Rect content = right < left ? new Rect(0, 0, source.getWidth(), source.getHeight())
                    : new Rect(left, top, right + 1, bottom + 1);
            int extent = Math.min(content.width(), content.height());
            int cx = content.centerX(), cy = content.centerY();
            Rect crop = this == NONE ? new Rect(0, 0, source.getWidth(), source.getHeight())
                    : new Rect(cx - extent / 2, cy - extent / 2, cx - extent / 2 + extent, cy - extent / 2 + extent);
            canvas.drawBitmap(source, crop, new Rect(0, 0, SIZE, SIZE), paint);
            if (this != NONE) {
                Bitmap mask = mask(visibleSize, corners, fileMask);
                try {
                    paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
                    canvas.drawBitmap(mask, 0, 0, paint);
                } finally { mask.recycle(); }
            }
            return result;
        } catch (RuntimeException error) {
            result.recycle();
            throw error;
        }
    }

    Bitmap mask() { return mask(SIZE - 2 * INSET); }

    Bitmap mask(int visibleSize) { return mask(visibleSize, null); }

    static int[] validCorners(int[] corners) {
        if (corners == null || corners.length != 4) throw new IllegalArgumentException(App.text(R.string.corners_invalid));
        for (int corner : corners) {
            if (corner < 0 || corner > MAX_CORNER) throw new IllegalArgumentException(App.text(R.string.corners_invalid));
        }
        return corners.clone();
    }

    /** Corners are used only by {@link #CUSTOM}; null means the editor defaults. */
    Bitmap mask(int visibleSize, int[] corners) {
        return mask(visibleSize, corners, null);
    }

    Bitmap mask(int visibleSize, int[] corners, Bitmap fileMask) {
        return mask(visibleSize, corners, fileMask, SIZE);
    }

    /** visibleSize uses preview coordinates; the silhouette scales proportionally to the export canvas. */
    Bitmap mask(int visibleSize, int[] corners, Bitmap fileMask, int canvasSize) {
        if (visibleSize < 1 || visibleSize > SIZE) throw new IllegalArgumentException(App.text(R.string.shape_size_invalid));
        if (this == NONE) throw new IllegalStateException(App.text(R.string.shape_not_selected));
        if (this == FILE) return IconMask.render(fileMask, visibleSize, canvasSize);
        Path path = new Path();
        switch (this) {
            case CIRCLE -> path.addCircle(.5f, .5f, .5f, Path.Direction.CW);
            case ROUNDED_SQUARE -> path.addRoundRect(0, 0, 1, 1, ROUND_RADIUS, ROUND_RADIUS, Path.Direction.CW);
            case SQUARE -> path.addRoundRect(0, 0, 1, 1, ROUND_RADIUS / 2, ROUND_RADIUS / 2, Path.Direction.CW);
            case CUSTOM -> {
                int[] c = validCorners(corners == null ? DEFAULT_CORNERS : corners);
                float[] radii = new float[8];
                for (int i = 0; i < 4; i++) radii[2 * i] = radii[2 * i + 1] = c[i] / 200f;
                path.addRoundRect(0, 0, 1, 1, radii, Path.Direction.CW);
            }
            case TEARDROP -> path.addRoundRect(0, 0, 1, 1,
                    new float[]{.5f, .5f, .5f, .5f, .12f, .12f, .5f, .5f}, Path.Direction.CW);
            case HEART -> {
                path.moveTo(.5f, 1);
                path.cubicTo(.40f, .94f, 0, .68f, 0, .32f);
                path.cubicTo(0, .02f, .30f, -.08f, .5f, .16f);
                path.cubicTo(.70f, -.08f, 1, .02f, 1, .32f);
                path.cubicTo(1, .68f, .60f, .94f, .5f, 1);
                path.close();
            }
            case STAR, HEXAGON, PENTAGON -> {
                int vertices = this == HEXAGON ? 6 : this == PENTAGON ? 5 : 10;
                float[] x = new float[vertices], y = new float[vertices];
                for (int i = 0; i < vertices; i++) {
                    double angle = -Math.PI / 2 + i * 2 * Math.PI / vertices;
                    float radius = this == STAR && i % 2 != 0 ? .31f : .5f;
                    x[i] = .5f + radius * (float) Math.cos(angle);
                    y[i] = .5f + radius * (float) Math.sin(angle);
                }
                // Short quadratic turns soften polygon and star tips.
                float corner = this == STAR ? .23f : .12f;
                for (int i = 0; i < vertices; i++) {
                    int previous = (i + vertices - 1) % vertices, next = (i + 1) % vertices;
                    float startX = x[i] + (x[previous] - x[i]) * corner;
                    float startY = y[i] + (y[previous] - y[i]) * corner;
                    if (i == 0) path.moveTo(startX, startY); else path.lineTo(startX, startY);
                    path.quadTo(x[i], y[i], x[i] + (x[next] - x[i]) * corner,
                            y[i] + (y[next] - y[i]) * corner);
                }
                path.close();
            }
            case SQUIRCLE -> {
                for (int step = 0; step < 360; step++) {
                    double angle = step * Math.PI / 180;
                    double cos = Math.cos(angle), sin = Math.sin(angle);
                    float x = .5f + .5f * (float) Math.copySign(Math.sqrt(Math.abs(cos)), cos);
                    float y = .5f + .5f * (float) Math.copySign(Math.sqrt(Math.abs(sin)), sin);
                    if (step == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
            }
            default -> throw new IllegalStateException(App.text(R.string.shape_unknown));
        }
        float extent = visibleSize * (float) canvasSize / SIZE;
        float inset = (canvasSize - extent) / 2f;
        Matrix transform = new Matrix();
        transform.setScale(extent, extent);
        transform.postTranslate(inset, inset);
        path.transform(transform);
        Bitmap bitmap = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        new Canvas(bitmap).drawPath(path, paint);
        return bitmap;
    }
}
