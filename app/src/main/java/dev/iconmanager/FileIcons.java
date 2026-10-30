package dev.iconmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class FileIcons {
    private static final String PREFIX = "fileicon:";

    static boolean isFile(String reference) { return reference.startsWith(PREFIX); }

    static File directory(Context context) { return new File(context.getFilesDir(), "file-icons"); }

    static Bitmap read(Context context, Uri uri) throws IOException {
        Bitmap source = android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(context.getContentResolver(), uri), (decoder, info, src) -> {
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    float scale = Math.min(1f, 512f / Math.max(w, h));
                    decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                });
        try { return fit(source, IconShape.SIZE); }
        finally { source.recycle(); }
    }

    static Bitmap fit(Bitmap source, int size) {
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        float scale = Math.min((float) size / source.getWidth(), (float) size / source.getHeight());
        float width = source.getWidth() * scale, height = source.getHeight() * scale;
        new Canvas(result).drawBitmap(source, null,
                new RectF((size - width) / 2, (size - height) / 2, (size + width) / 2, (size + height) / 2),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    static String save(Context context, Bitmap bitmap) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException(App.text(R.string.icon_save_error));
        byte[] bytes = output.toByteArray();
        String reference = PREFIX + "i" + digest(bytes);
        restore(context, reference, bytes);
        return reference;
    }

    static byte[] bytes(Context context, String reference) throws IOException {
        byte[] bytes = Files.readAllBytes(file(directory(context), reference).toPath());
        validate(reference, bytes);
        return bytes;
    }

    static void restore(Context context, String reference, byte[] bytes) throws IOException {
        validate(reference, bytes);
        File destination = file(directory(context), reference);
        Files.createDirectories(destination.getParentFile().toPath());
        AtomicFile atomic = new AtomicFile(destination);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(bytes);
            atomic.finishWrite(output);
        } catch (IOException error) {
            atomic.failWrite(output);
            throw error;
        }
    }

    static Bitmap render(File directory, String reference, int size) throws IOException {
        if (directory == null) throw new IOException(App.text(R.string.icon_storage_error));
        byte[] bytes = Files.readAllBytes(file(directory, reference).toPath());
        validate(reference, bytes);
        Bitmap source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (source == null) throw new IOException(App.text(R.string.icon_read_error));
        try { return fit(source, size); }
        finally { source.recycle(); }
    }

    private static File file(File directory, String reference) throws IOException {
        if (!reference.matches("fileicon:i[0-9a-f]{64}")) throw new IOException(App.text(R.string.icon_reference_error));
        return new File(directory, reference.substring(PREFIX.length()) + ".png");
    }

    private static void validate(String reference, byte[] bytes) throws IOException {
        if (!reference.equals(PREFIX + "i" + digest(bytes))) throw new IOException(App.text(R.string.icon_corrupt));
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth != IconShape.SIZE || options.outHeight != IconShape.SIZE
                || !"image/png".equals(options.outMimeType)) throw new IOException(App.text(R.string.icon_image_invalid));
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
