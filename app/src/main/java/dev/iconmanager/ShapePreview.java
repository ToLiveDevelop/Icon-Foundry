package dev.iconmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import java.io.File;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ShapePreview implements AutoCloseable {
    static final int WIDTH = 720, HEIGHT = 440;
    private final List<Bitmap> originals = new ArrayList<>(), unmasked = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private Bitmap wallpaper, overlay;
    private final int visibleSize;

    private ShapePreview(int visibleSize) { this.visibleSize = visibleSize; }

    static List<IconRepository.App> examples(List<IconRepository.App> apps, IconRepository.Pack pack,
                                             Map<String, String> overrides, java.util.Locale locale) {
        Collator collator = Collator.getInstance(locale);
        Comparator<IconRepository.App> order = Comparator.comparing(IconRepository.App::label, collator)
                .thenComparing(IconRepository.App::key);
        List<IconRepository.App> sorted = apps.stream().sorted(order).toList();
        Map<String, IconRepository.App> selected = new LinkedHashMap<>();
        for (IconRepository.App app : sorted) {
            if (pack == null || pack.selected(app, overrides) == null) selected.putIfAbsent(app.key(), app);
            if (selected.size() == 6) break;
        }
        if (selected.size() < 6) for (IconRepository.App app : sorted) {
            selected.putIfAbsent(app.key(), app);
            if (selected.size() == 6) break;
        }
        return List.copyOf(selected.values());
    }

    static ShapePreview load(Context context, List<IconRepository.App> examples, int visibleSize,
                             String wallpaperPath, String overlayReference) throws IOException {
        ShapePreview preview = new ShapePreview(visibleSize);
        try {
            if (examples.isEmpty()) throw new IOException(context.getString(R.string.shape_no_apps));
            for (IconRepository.App app : examples) {
                var icon = IconRepository.unthemedIcon(context.getPackageManager(), app.component());
                preview.originals.add(IconShape.previewSource(icon, false));
                preview.unmasked.add(IconShape.previewSource(icon, true));
                preview.labels.add(app.label());
            }
            preview.overlay = IconOverlay.load(context, overlayReference);
            if (!wallpaperPath.isEmpty()) {
                File file = new File(context.getFilesDir(), wallpaperPath);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), bounds);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > WIDTH * 2)
                    options.inSampleSize *= 2;
                preview.wallpaper = BitmapFactory.decodeFile(file.getPath(), options);
                if (preview.wallpaper == null) throw new IOException(context.getString(R.string.wallpaper_read_error));
            }
            return preview;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            preview.close();
            throw new IOException(context.getString(R.string.shape_preview_error), error);
        } catch (IOException | RuntimeException error) {
            preview.close();
            throw error;
        }
    }

    Bitmap render(IconShape shape) {
        Bitmap result = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (wallpaper == null) {
            paint.setShader(new LinearGradient(0, 0, WIDTH, HEIGHT,
                    new int[]{0xFF344C79, 0xFF294C54, 0xFF182638}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, WIDTH, HEIGHT, paint);
            paint.setShader(null);
        } else {
            float scale = Math.max((float) WIDTH / wallpaper.getWidth(), (float) HEIGHT / wallpaper.getHeight());
            int width = Math.round(WIDTH / scale), height = Math.round(HEIGHT / scale);
            int left = (wallpaper.getWidth() - width) / 2, top = (wallpaper.getHeight() - height) / 2;
            canvas.drawBitmap(wallpaper, new Rect(left, top, left + width, top + height),
                    new Rect(0, 0, WIDTH, HEIGHT), paint);
        }
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(25);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(4, 0, 2, Color.BLACK);
        for (int i = 0; i < originals.size(); i++) {
            Bitmap icon = shape.preview((shape == IconShape.NONE ? originals : unmasked).get(i), visibleSize);
            try {
                IconOverlay.draw(icon, overlay);
                int cx = 120 + i % 3 * 240, top = 40 + i / 3 * 208;
                canvas.drawBitmap(icon, null, new Rect(cx - 60, top, cx + 60, top + 120), paint);
                String label = TextUtils.ellipsize(labels.get(i), text, 210, TextUtils.TruncateAt.END).toString();
                canvas.drawText(label, cx, top + 158, text);
            } finally { icon.recycle(); }
        }
        return result;
    }

    @Override public void close() {
        for (Bitmap bitmap : originals) bitmap.recycle();
        for (Bitmap bitmap : unmasked) bitmap.recycle();
        if (wallpaper != null) wallpaper.recycle();
        if (overlay != null) overlay.recycle();
    }
}
