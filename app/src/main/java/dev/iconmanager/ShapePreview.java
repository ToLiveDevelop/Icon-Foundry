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
    private final List<Bitmap> originals = new ArrayList<>(), unmasked = new ArrayList<>(), packIcons = new ArrayList<>();
    private final List<String> labels = new ArrayList<>(), packLabels = new ArrayList<>();
    private Bitmap wallpaper, overlay, background, shapeMask;
    private final int visibleSize;
    private int overlayScale = 100;

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
        return load(context, examples, visibleSize, wallpaperPath, overlayReference, 100);
    }

    static ShapePreview load(Context context, List<IconRepository.App> examples, int visibleSize,
                             String wallpaperPath, String overlayReference, int overlayScale) throws IOException {
        return load(context, examples, visibleSize, wallpaperPath, overlayReference, overlayScale, "");
    }

    static ShapePreview load(Context context, List<IconRepository.App> examples, int visibleSize,
                             String wallpaperPath, String overlayReference, int overlayScale,
                             String backgroundReference) throws IOException {
        return load(context, examples, visibleSize, wallpaperPath, overlayReference, overlayScale, backgroundReference, "");
    }

    static ShapePreview load(Context context, List<IconRepository.App> examples, int visibleSize,
                             String wallpaperPath, String overlayReference, int overlayScale,
                             String backgroundReference, String shapeMaskReference) throws IOException {
        return load(context, examples, visibleSize, wallpaperPath, overlayReference, overlayScale,
                backgroundReference, shapeMaskReference, List.of());
    }

    static ShapePreview load(Context context, List<IconRepository.App> examples, int visibleSize,
                             String wallpaperPath, String overlayReference, int overlayScale,
                             String backgroundReference, String shapeMaskReference,
                             List<IconFilters.Filter> filters) throws IOException {
        ShapePreview preview = new ShapePreview(visibleSize);
        try {
            if (examples.isEmpty()) throw new IOException(context.getString(R.string.shape_no_apps));
            for (IconRepository.App app : examples) preview.addSystem(context, app, filters);
            preview.loadResources(context, wallpaperPath, overlayReference, backgroundReference, shapeMaskReference);
            preview.overlayScale = overlayScale;
            return preview;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            preview.close();
            throw new IOException(context.getString(R.string.shape_preview_error), error);
        } catch (IOException | RuntimeException error) {
            preview.close();
            throw error;
        }
    }

    static ShapePreview loadStyle(Context context, List<IconRepository.App> apps, IconRepository.Pack pack,
                                  Map<String, String> overrides, int visibleSize, String wallpaperPath,
                                  String overlayReference, String backgroundReference, String shapeMaskReference,
                                  List<IconFilters.Filter> filters) throws IOException {
        return loadStyle(context, apps, pack, overrides, visibleSize, wallpaperPath, overlayReference,
                backgroundReference, shapeMaskReference, filters, false);
    }

    static ShapePreview loadStyle(Context context, List<IconRepository.App> apps, IconRepository.Pack pack,
                                  Map<String, String> overrides, int visibleSize, String wallpaperPath,
                                  String overlayReference, String backgroundReference, String shapeMaskReference,
                                  List<IconFilters.Filter> filters, boolean systemOnly) throws IOException {
        ShapePreview preview = new ShapePreview(visibleSize);
        try {
            Collator collator = Collator.getInstance(context.getResources().getConfiguration().getLocales().get(0));
            List<IconRepository.App> sorted = apps.stream().sorted(Comparator
                    .comparing(IconRepository.App::label, collator).thenComparing(IconRepository.App::key)).toList();
            java.util.Set<String> selected = new java.util.HashSet<>();
            java.util.Set<String> systemExamples = new java.util.HashSet<>(), packedExamples = new java.util.HashSet<>();
            for (IconRepository.App app : sorted) {
                if (!selected.add(app.component().getPackageName())) continue;
                String name = pack == null ? null : pack.selected(app, overrides);
                if (name == null && preview.originals.size() < 3) {
                    preview.addSystem(context, app, filters);
                    systemExamples.add(app.key());
                }
                else if (!systemOnly && name != null && preview.packIcons.size() < 3) {
                    preview.packIcons.add(pack.render(name, 192));
                    preview.packLabels.add(app.label());
                    packedExamples.add(name);
                }
                if (preview.originals.size() == 3 && (systemOnly || preview.packIcons.size() == 3)) break;
            }
            if (preview.originals.size() < 3) for (IconRepository.App app : sorted) {
                if (!systemExamples.add(app.key())) continue;
                preview.addSystem(context, app, filters);
                if (preview.originals.size() == 3) break;
            }
            if (systemOnly) for (IconRepository.App app : sorted) {
                if (preview.originals.size() == 6) break;
                if (systemExamples.add(app.key())) preview.addSystem(context, app, filters);
            }
            if (!systemOnly && pack != null && preview.packIcons.size() < 3) {
                for (IconRepository.Icon icon : pack.icons.values()) {
                    if (!packedExamples.add(icon.name())) continue;
                    preview.packIcons.add(pack.render(icon.name(), IconShape.SIZE));
                    preview.packLabels.add(icon.name());
                    if (preview.packIcons.size() == 3) break;
                }
            }
            if (preview.originals.isEmpty() && preview.packIcons.isEmpty())
                throw new IOException(context.getString(R.string.shape_no_apps));
            preview.loadResources(context, wallpaperPath, overlayReference, backgroundReference, shapeMaskReference);
            return preview;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            preview.close();
            throw new IOException(context.getString(R.string.shape_preview_error), error);
        } catch (IOException | RuntimeException error) {
            preview.close();
            throw error;
        }
    }

    private void addSystem(Context context, IconRepository.App app, List<IconFilters.Filter> filters)
            throws android.content.pm.PackageManager.NameNotFoundException {
        var icon = IconRepository.unthemedIcon(context.getPackageManager(), app.component());
        Bitmap original = IconShape.previewSource(icon, false);
        try { originals.add(IconShape.NONE.prepare(original, IconShape.SIZE, filters)); }
        finally { original.recycle(); }
        Bitmap unmasked = IconShape.previewSource(icon, true);
        try { this.unmasked.add(IconShape.ROUNDED_SQUARE.prepare(unmasked, IconShape.SIZE, filters)); }
        finally { unmasked.recycle(); }
        labels.add(app.label());
    }

    private void loadResources(Context context, String wallpaperPath, String overlayReference,
                               String backgroundReference, String shapeMaskReference) throws IOException {
        overlay = IconOverlay.load(context, overlayReference);
        background = IconBackground.load(context, backgroundReference);
        shapeMask = shapeMaskReference.isEmpty() ? null : IconMask.load(context, shapeMaskReference);
        if (!wallpaperPath.isEmpty()) {
            File file = new File(context.getFilesDir(), wallpaperPath);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > WIDTH * 2)
                options.inSampleSize *= 2;
            wallpaper = BitmapFactory.decodeFile(file.getPath(), options);
            if (wallpaper == null) throw new IOException(context.getString(R.string.wallpaper_read_error));
        }
    }

    Bitmap render(IconShape shape) {
        return render(shape, overlayScale);
    }

    Bitmap render(IconShape shape, int overlayScale) {
        return render(shape, null, overlayScale, false);
    }

    Bitmap render(IconShape shape, int[] corners, int overlayScale, boolean overlayClip) {
        return render(shape, corners, overlayScale, overlayClip, 100, true);
    }

    Bitmap render(IconShape shape, int[] corners, int overlayScale, boolean overlayClip,
                  int backgroundScale, boolean backgroundClip) {
        return render(shape, corners, overlayScale, overlayClip, backgroundScale, backgroundClip, null);
    }

    Bitmap render(IconShape shape, int[] corners, int overlayScale, boolean overlayClip,
                  int backgroundScale, boolean backgroundClip, Bitmap fileMask) {
        return render(shape, corners, overlayScale, overlayClip, backgroundScale, backgroundClip, fileMask, 100);
    }

    Bitmap render(IconShape shape, int[] corners, int overlayScale, boolean overlayClip,
                  int backgroundScale, boolean backgroundClip, Bitmap fileMask, int maskScale) {
        Bitmap source = fileMask == null ? shapeMask : fileMask;
        Bitmap scaledMask = shape == IconShape.FILE && source != null && maskScale != 100
                ? IconMask.scaled(source, maskScale) : null;
        try {
            return renderScene(shape, corners, overlayScale, overlayClip, backgroundScale, backgroundClip,
                    scaledMask == null ? source : scaledMask, false, false, false, 100);
        } finally { if (scaledMask != null) scaledMask.recycle(); }
    }

    Bitmap renderStyle(IconShape shape, int[] corners, int overlayScale, boolean overlayClip,
                       int backgroundScale, boolean backgroundClip, Bitmap fileMask, int maskScale,
                       boolean overlayAll, boolean backgroundAll, int iconScale) {
        return renderStyle(shape, corners, overlayScale, overlayClip, backgroundScale, backgroundClip,
                fileMask, maskScale, overlayAll, backgroundAll, iconScale, true, HEIGHT);
    }

    Bitmap renderStyle(IconShape shape, int[] corners, int overlayScale, boolean overlayClip,
                       int backgroundScale, boolean backgroundClip, Bitmap fileMask, int maskScale,
                       boolean overlayAll, boolean backgroundAll, int iconScale, boolean mixed, int height) {
        Bitmap source = fileMask == null ? shapeMask : fileMask;
        Bitmap scaledMask = shape == IconShape.FILE && source != null && maskScale != 100
                ? IconMask.scaled(source, maskScale) : null;
        try {
            return renderScene(shape, corners, overlayScale, overlayClip, backgroundScale, backgroundClip,
                    scaledMask == null ? source : scaledMask, mixed, overlayAll, backgroundAll, iconScale, height);
        } finally { if (scaledMask != null) scaledMask.recycle(); }
    }

    private Bitmap renderScene(IconShape shape, int[] corners, int overlayScale, boolean overlayClip,
                               int backgroundScale, boolean backgroundClip, Bitmap activeMask, boolean mixed,
                               boolean overlayAll, boolean backgroundAll, int iconScale) {
        return renderScene(shape, corners, overlayScale, overlayClip, backgroundScale, backgroundClip,
                activeMask, mixed, overlayAll, backgroundAll, iconScale, HEIGHT);
    }

    private Bitmap renderScene(IconShape shape, int[] corners, int overlayScale, boolean overlayClip,
                               int backgroundScale, boolean backgroundClip, Bitmap activeMask, boolean mixed,
                               boolean overlayAll, boolean backgroundAll, int iconScale, int outputHeight) {
        Bitmap result = Bitmap.createBitmap(WIDTH, outputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (wallpaper == null) {
            paint.setShader(new LinearGradient(0, 0, WIDTH, outputHeight,
                    new int[]{0xFF344C79, 0xFF294C54, 0xFF182638}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, WIDTH, outputHeight, paint);
            paint.setShader(null);
        } else {
            float scale = Math.max((float) WIDTH / wallpaper.getWidth(), (float) outputHeight / wallpaper.getHeight());
            int width = Math.max(1, Math.round(WIDTH / scale)), height = Math.max(1, Math.round(outputHeight / scale));
            int left = (wallpaper.getWidth() - width) / 2, top = (wallpaper.getHeight() - height) / 2;
            canvas.drawBitmap(wallpaper, new Rect(left, top, left + width, top + height),
                    new Rect(0, 0, WIDTH, outputHeight), paint);
        }
        float sceneScale = Math.min(1f, (float) outputHeight / HEIGHT);
        canvas.translate((WIDTH - WIDTH * sceneScale) / 2, (outputHeight - HEIGHT * sceneScale) / 2);
        canvas.scale(sceneScale, sceneScale);
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(25);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(4, 0, 2, Color.BLACK);
        Bitmap scaledOverlay = null, layer = null, backdrop = null;
        try {
            if (overlay != null) {
                scaledOverlay = IconOverlay.scaled(overlay, overlayScale);
                layer = IconOverlay.border(scaledOverlay, shape, corners, visibleSize, overlayClip, activeMask);
            }
            if (background != null) {
                Bitmap scaled = IconOverlay.scaled(background, backgroundScale, R.string.background_scale_invalid);
                try { backdrop = IconBackground.render(scaled, shape, corners, visibleSize, backgroundClip, activeMask); }
                finally { scaled.recycle(); }
            }
            int slots = mixed ? 6 : originals.size();
            for (int slot = 0; slot < slots; slot++) {
                boolean packed = mixed && slot >= 3;
                int index = packed ? slot - 3 : slot;
                if (packed ? index >= packIcons.size() : index >= originals.size()) continue;
                Bitmap icon;
                String label;
                if (packed) {
                    icon = IconRepository.Pack.scaleArtwork(
                            packIcons.get(index).copy(Bitmap.Config.ARGB_8888, true), iconScale);
                    label = packLabels.get(index);
                    if (overlayAll) IconOverlay.draw(icon, scaledOverlay, overlayClip);
                    if (backgroundAll) IconBackground.draw(icon, backdrop);
                } else {
                    icon = shape.previewPrepared((shape == IconShape.NONE ? originals : unmasked).get(index),
                            visibleSize, corners, activeMask);
                    label = labels.get(index);
                    IconOverlay.draw(icon, layer);
                    IconBackground.draw(icon, backdrop);
                }
                try {
                    int cx = 120 + slot % 3 * 240, top = 40 + slot / 3 * 208;
                    canvas.drawBitmap(icon, null, new Rect(cx - 60, top, cx + 60, top + 120), paint);
                    String caption = TextUtils.ellipsize(label, text, 210, TextUtils.TruncateAt.END).toString();
                    canvas.drawText(caption, cx, top + 158, text);
                } finally { icon.recycle(); }
            }
        } catch (RuntimeException error) {
            result.recycle();
            throw error;
        } finally {
            if (scaledOverlay != null) scaledOverlay.recycle();
            if (layer != null) layer.recycle();
            if (backdrop != null) backdrop.recycle();
        }
        return result;
    }

    Bitmap shapeMask() { return shapeMask; }

    @Override public void close() {
        for (Bitmap bitmap : originals) bitmap.recycle();
        for (Bitmap bitmap : unmasked) bitmap.recycle();
        for (Bitmap bitmap : packIcons) bitmap.recycle();
        if (wallpaper != null) wallpaper.recycle();
        if (overlay != null) overlay.recycle();
        if (background != null) background.recycle();
        if (shapeMask != null) shapeMask.recycle();
    }
}
