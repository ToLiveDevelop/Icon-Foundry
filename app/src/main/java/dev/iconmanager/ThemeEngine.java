package dev.iconmanager;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class ThemeEngine {
    record Format(String name, int layerSize, int iconSize, int contentSize) {}
    record Result(Uri uri, int apps, String filename, boolean clearFolderBackground) {}
    record Built(File file, int apps) {}
    interface Progress { void update(int done, int total); }
    private static final long MAX_TEMPLATE = 256L * 1024 * 1024;
    private static final long MAX_ICONS = 128L * 1024 * 1024;
    // Built-in, device-verified HnTheme profile; exports never depend on a user-supplied sample.
    static final int EXPORT_ICON_SIZE = 192;
    static final String LAUNCHER = "com.hihonor.android.launcher";
    private static final String FOLDER_COLORS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<resources><color name=\"magic_gray_4\">#00000000</color></resources>\n";

    static Format inspect(Context context, Uri uri) throws Exception {
        File theme = File.createTempFile("sample-", ".hnt", context.getCacheDir());
        File icons = File.createTempFile("sample-icons-", ".zip", context.getCacheDir());
        try {
            try (InputStream input = require(context.getContentResolver().openInputStream(uri));
                 OutputStream output = new FileOutputStream(theme)) {
                copyLimited(input, output, MAX_TEMPLATE);
            }
            try (ZipFile zip = new ZipFile(theme)) {
                ZipEntry description = zip.getEntry("description.xml");
                ZipEntry inner = zip.getEntry("icons");
                if (description == null || inner == null) throw invalidTemplate(context);
                try (InputStream input = zip.getInputStream(description)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    copyLimited(input, bytes, 1024 * 1024);
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(new java.io.ByteArrayInputStream(bytes.toByteArray()), "UTF-8");
                    boolean root = false;
                    for (int event = parser.nextToken(); event != XmlPullParser.END_DOCUMENT;
                         event = parser.nextToken()) {
                        if (event == XmlPullParser.DOCDECL) throw invalidTemplate(context);
                        if (event == XmlPullParser.START_TAG && !root) {
                            if (!"HnTheme".equals(parser.getName()) && !"HwTheme".equals(parser.getName())) {
                                throw invalidTemplate(context);
                            }
                            root = true;
                        }
                    }
                    if (!root) throw invalidTemplate(context);
                }
                try (InputStream input = zip.getInputStream(inner);
                     OutputStream output = new FileOutputStream(icons)) {
                    copyLimited(input, output, MAX_ICONS);
                }
            }
            int layerSize = 0;
            int iconSize;
            int contentSize;
            try (ZipFile zip = new ZipFile(icons)) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().contains("/") && entry.getName().endsWith(".png")
                            && IconRepository.safeName(entry.getName().substring(0, entry.getName().length() - 4))
                            && !entry.getName().startsWith("icon_")) {
                        int size = imageSize(zip, entry);
                        return new Format(documentName(context.getContentResolver(), uri), size, size, size);
                    }
                    if (entry.getName().endsWith("/foreground.png")
                            && zip.getEntry(entry.getName().replace("/foreground.png", "/background.png")) != null) {
                        layerSize = imageSize(zip, entry);
                        int backgroundSize = imageSize(zip,
                                zip.getEntry(entry.getName().replace("/foreground.png", "/background.png")));
                        if (backgroundSize != layerSize) throw invalidTemplate(context);
                        break;
                    }
                }
                ZipEntry mask = zip.getEntry("icon_mask.png");
                if (layerSize == 0 || mask == null) throw invalidTemplate(context);
                iconSize = imageSize(zip, mask);
                Bitmap bitmap;
                try (InputStream input = zip.getInputStream(mask)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    copyLimited(input, bytes, 4L * 1024 * 1024);
                    bitmap = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size());
                }
                if (bitmap == null) throw invalidTemplate(context);
                int left = iconSize, top = iconSize, right = -1, bottom = -1;
                for (int y = 0; y < iconSize; y++) {
                    for (int x = 0; x < iconSize; x++) {
                        if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                            left = Math.min(left, x); right = Math.max(right, x);
                            top = Math.min(top, y); bottom = Math.max(bottom, y);
                        }
                    }
                }
                bitmap.recycle();
                contentSize = Math.max(right - left + 1, bottom - top + 1);
                if (contentSize < 32 || contentSize > iconSize || iconSize > layerSize) {
                    throw invalidTemplate(context);
                }
            }
            return new Format(documentName(context.getContentResolver(), uri), layerSize, iconSize, contentSize);
        } finally {
            removeTemp(theme);
            removeTemp(icons);
        }
    }

    private static int imageSize(ZipFile zip, ZipEntry entry) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = zip.getInputStream(entry)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            copyLimited(input, bytes, 4L * 1024 * 1024);
            BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size(), options);
        }
        if (options.outWidth < 32 || options.outWidth > 1024 || options.outWidth != options.outHeight) {
            throw new IOException("Недопустимый размер иконок в образце.");
        }
        return options.outWidth;
    }

    static String filename(String raw) {
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 100 || value.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")
                || value.startsWith(".") || value.endsWith(".") || value.endsWith(" ")) {
            throw new IllegalArgumentException("filename");
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(".hnt")) value = value.substring(0, value.length() - 4) + ".hnt";
        else value += ".hnt";
        if (value.equalsIgnoreCase(".hnt")) throw new IllegalArgumentException("filename");
        return value;
    }

    static void validateTree(Context context, Uri tree) throws IOException {
        if (!"content".equals(tree.getScheme())
                || !"com.android.externalstorage.documents".equals(tree.getAuthority())
                || !DocumentsContract.isTreeUri(tree)
                || !"primary:Honor/Themes".equalsIgnoreCase(DocumentsContract.getTreeDocumentId(tree))) {
            throw new IOException(context.getString(R.string.destination_error));
        }
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = context.getContentResolver().query(doc,
                new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS},
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()
                    || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0))
                    || (cursor.getLong(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) {
                throw new IOException(context.getString(R.string.access_lost));
            }
        }
    }

    static boolean exists(ContentResolver resolver, Uri tree, String filename) throws IOException {
        return findDocument(resolver, tree, filename) != null;
    }

    static Uri findDocument(ContentResolver resolver, Uri tree, String filename) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = resolver.query(children,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                null, null, null)) {
            if (cursor == null) throw new IOException("Не удалось прочитать каталог экспорта.");
            while (cursor.moveToNext()) {
                if (filename.equalsIgnoreCase(cursor.getString(0))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(1));
                }
            }
        }
        return null;
    }

    static Map<String, String> targets(List<IconRepository.App> apps, IconRepository.Pack pack,
                                       Map<String, String> overrides) throws IOException {
        Map<String, List<IconRepository.App>> groups = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> classOwners = new LinkedHashMap<>();
        for (IconRepository.App app : apps) {
            groups.computeIfAbsent(app.component().getPackageName(), key -> new ArrayList<>()).add(app);
            classOwners.computeIfAbsent(app.component().getClassName(), key -> new java.util.HashSet<>())
                    .add(app.component().getPackageName());
        }
        Map<String, String> targets = new LinkedHashMap<>();
        for (var group : groups.entrySet()) {
            String common = pack.selected(group.getValue().get(0), overrides);
            boolean same = true;
            for (IconRepository.App app : group.getValue()) {
                String selected = pack.selected(app, overrides);
                if (!java.util.Objects.equals(common, selected)) same = false;
            }
            // Prefer package keys: unrelated apps can share an activity class (e.g. Calendar).
            if (same) {
                if (common != null) addTarget(targets, group.getKey(), common);
            } else {
                boolean allThemed = true;
                for (IconRepository.App app : group.getValue()) {
                    if (pack.selected(app, overrides) == null) allThemed = false;
                }
                if (allThemed) {
                    String base = common;
                    for (IconRepository.App app : group.getValue()) {
                        if (IconRepository.Pack.isLegacyContacts(app.component())
                                && app.component().getClassName().endsWith(".PeopleActivity")) {
                            base = pack.selected(app, overrides);
                        }
                    }
                    addTarget(targets, group.getKey(), base);
                }
                for (IconRepository.App app : group.getValue()) {
                    String selected = pack.selected(app, overrides);
                    if (selected == null) continue;
                    String activity = app.component().getClassName();
                    if (classOwners.get(activity).size() > 1) {
                        throw new IOException("Формат HONOR не различает общую activity " + activity
                                + ". Назначьте одну иконку всем ярлыкам этого приложения.");
                    }
                    addTarget(targets, activity, selected);
                }
            }
        }
        return targets;
    }

    static Map<String, String> exportTargets(List<IconRepository.App> apps, IconRepository.Pack pack,
                                              Map<String, String> overrides, boolean fullPack) throws IOException {
        apps = exportApps(apps, overrides, fullPack);
        Map<String, String> result = new LinkedHashMap<>();
        if (fullPack) {
            // Future installations resolve through their package key, not another app's activity name.
            result.putAll(pack.byPackage);
            for (IconRepository.App app : apps) result.remove(app.component().getPackageName());
        }
        Map<String, String> installed = targets(apps, pack, overrides);
        for (var entry : installed.entrySet()) addTarget(result, entry.getKey(), entry.getValue());
        return result;
    }

    static int exportCount(List<IconRepository.App> apps, IconRepository.Pack pack,
                           Map<String, String> overrides, boolean fullPack) {
        apps = exportApps(apps, overrides, fullPack);
        int count = 0;
        java.util.Set<String> installed = new java.util.HashSet<>();
        for (IconRepository.App app : apps) {
            installed.add(app.component().getPackageName());
            if (pack.selected(app, overrides) != null) count++;
        }
        if (fullPack) for (String packageName : pack.byPackage.keySet()) if (!installed.contains(packageName)) count++;
        return count;
    }

    private static List<IconRepository.App> exportApps(List<IconRepository.App> apps,
                                                       Map<String, String> overrides, boolean fullPack) {
        if (!fullPack) return apps;
        List<IconRepository.App> result = new ArrayList<>(apps);
        java.util.Set<String> known = new java.util.HashSet<>();
        for (IconRepository.App app : apps) known.add(app.key());
        for (String key : overrides.keySet()) {
            if (known.contains(key)) continue;
            var component = IconRepository.component(key);
            if (component == null) throw new IllegalArgumentException("Некорректное сохранённое приложение: " + key);
            result.add(new IconRepository.App(component, component.getPackageName()));
        }
        return result;
    }

    private static void addTarget(Map<String, String> targets, String name, String icon) throws IOException {
        String old = targets.putIfAbsent(name, icon);
        if (old != null && !old.equals(icon)) {
            throw new IOException("Конфликт имён компонентов в формате HONOR: " + name);
        }
    }

    static Built build(Context context, String title, boolean fullPack, IconShape shape, boolean clearFolderBackground,
                       IconRepository.Pack pack,
                       List<IconRepository.App> apps, Map<String, String> overrides,
                       Bitmap cover, Progress progress) throws Exception {
        return build(context, title, fullPack, shape, clearFolderBackground, pack, apps, overrides, cover, null, progress);
    }

    static Built build(Context context, String title, boolean fullPack, IconShape shape, boolean clearFolderBackground,
                       IconRepository.Pack pack, List<IconRepository.App> apps, Map<String, String> overrides,
                       Bitmap cover, File wallpaper, Progress progress) throws Exception {
        Map<String, String> targets = exportTargets(apps, pack, overrides, fullPack);
        int count = exportCount(apps, pack, overrides, fullPack);
        if (count == 0) throw new IOException(context.getString(R.string.empty_export));
        int maskSize = shape == IconShape.NONE ? IconShape.SIZE - 2 * IconShape.INSET : pack.fallbackSize();
        byte[] wallpaperBytes = wallpaper == null ? null : java.nio.file.Files.readAllBytes(wallpaper.toPath());
        if (wallpaperBytes != null) verifyWallpaper(wallpaperBytes);
        File nested = File.createTempFile("icons-", ".zip", context.getCacheDir());
        File result = File.createTempFile("theme-", ".hnt", context.getCacheDir());
        boolean complete = false;
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(nested))) {
                int done = 0;
                for (var target : targets.entrySet()) {
                    if (!IconRepository.safeName(target.getKey())) throw new IOException("Некорректное имя компонента.");
                    Bitmap icon = pack.render(target.getValue(), EXPORT_ICON_SIZE);
                    try { put(zip, target.getKey() + ".png", png(icon)); }
                    finally { icon.recycle(); }
                    progress.update(++done, targets.size());
                }
                if (shape != IconShape.NONE) {
                    Bitmap mask = shape.mask(maskSize);
                    try { put(zip, "icon_mask.png", png(mask)); }
                    finally { mask.recycle(); }
                }
            }
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(result))) {
                put(zip, "preview/", new byte[0]);
                put(zip, "description.xml", description(title, pack.label));
                if (clearFolderBackground) put(zip, LAUNCHER, folderColors());
                if (wallpaperBytes != null) {
                    put(zip, "wallpaper/", new byte[0]);
                    put(zip, "wallpaper/home_wallpaper_0.jpg", wallpaperBytes);
                    put(zip, "wallpaper/unlock_wallpaper_0.jpg", wallpaperBytes);
                }
                zip.putNextEntry(new ZipEntry("icons"));
                try (InputStream input = new FileInputStream(nested)) { input.transferTo(zip); }
                zip.closeEntry();
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                if (!cover.compress(Bitmap.CompressFormat.JPEG, 92, jpeg)) throw new IOException("Ошибка кодирования обложки.");
                put(zip, "preview/cover.jpg", jpeg.toByteArray());
                put(zip, "preview/preview_icons_0.jpg", jpeg.toByteArray());
                Bitmap small = Bitmap.createScaledBitmap(cover, 180, 320, true);
                try {
                    ByteArrayOutputStream thumbnail = new ByteArrayOutputStream();
                    if (!small.compress(Bitmap.CompressFormat.JPEG, 90, thumbnail)) throw new IOException("Ошибка миниатюры.");
                    put(zip, "preview/icon_small.jpg", thumbnail.toByteArray());
                } finally { if (small != cover) small.recycle(); }
            }
            verifyArchive(result, targets.size(), shape, clearFolderBackground, maskSize, wallpaper != null);
            complete = true;
            return new Built(result, count);
        } finally {
            removeTemp(nested);
            if (!complete) removeTemp(result);
        }
    }

    static Result export(Context context, String title, String filename, boolean fullPack, IconShape shape,
                         boolean clearFolderBackground,
                         IconRepository.Pack pack, List<IconRepository.App> apps,
                         Map<String, String> overrides, Bitmap cover, Uri tree, Uri replace, Progress progress) throws Exception {
        return export(context, title, filename, fullPack, shape, clearFolderBackground, pack, apps, overrides,
                cover, tree, replace, null, progress);
    }

    static Result export(Context context, String title, String filename, boolean fullPack, IconShape shape,
                         boolean clearFolderBackground, IconRepository.Pack pack, List<IconRepository.App> apps,
                         Map<String, String> overrides, Bitmap cover, Uri tree, Uri replace,
                         File wallpaper, Progress progress) throws Exception {
        validateTree(context, tree);
        ContentResolver resolver = context.getContentResolver();
        Uri existing = findDocument(resolver, tree, filename);
        if (existing != null && !existing.equals(replace)) throw new IOException(context.getString(R.string.file_exists));
        Built built = build(context, title, fullPack, shape, clearFolderBackground, pack, apps, overrides, cover, wallpaper, progress);
        File result = built.file();
        Uri created = null;
        try {
            existing = findDocument(resolver, tree, filename);
            if (existing != null && !existing.equals(replace)) throw new IOException(context.getString(R.string.file_exists));
            if (existing != null) {
                replaceVerified(context, existing, result);
                return new Result(existing, built.apps(), filename, clearFolderBackground);
            }
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", filename);
            if (created == null) throw new IOException("Провайдер хранилища не создал файл.");
            if (!filename.equals(documentName(resolver, created))) throw new IOException(context.getString(R.string.file_exists));
            try (InputStream input = new FileInputStream(result);
                 OutputStream output = require(resolver.openOutputStream(created, "wt"))) {
                input.transferTo(output);
            }
            byte[] expected;
            try (InputStream input = new FileInputStream(result)) { expected = digest(input); }
            try (InputStream input = require(resolver.openInputStream(created))) {
                if (!MessageDigest.isEqual(expected, digest(input))) throw new IOException("Записанный файл не прошёл проверку целостности.");
            }
            return new Result(created, built.apps(), filename, clearFolderBackground);
        } catch (Exception error) {
            if (created != null) {
                try {
                    if (!DocumentsContract.deleteDocument(resolver, created)) {
                        error.addSuppressed(new IOException("Неполный файл не удалён: " + created));
                    }
                } catch (Exception cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        } finally {
            removeTemp(result);
        }
    }

    private static byte[] folderColors() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            byte[] xml = FOLDER_COLORS.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // CardFolderUtils tints the preview mask with this launcher-local color in both modes.
            put(zip, "theme.xml", xml);
            put(zip, "night/theme.xml", xml);
        }
        return bytes.toByteArray();
    }

    static void replaceVerified(Context context, Uri uri, File replacement) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        File backup = File.createTempFile("original-theme-", ".hnt", context.getFilesDir());
        boolean keepBackup = false;
        try {
            try (InputStream input = require(resolver.openInputStream(uri));
                 OutputStream output = new FileOutputStream(backup)) { input.transferTo(output); }
            try {
                writeVerified(resolver, uri, replacement);
            } catch (Exception error) {
                try { writeVerified(resolver, uri, backup); }
                catch (Exception restore) {
                    keepBackup = true;
                    error.addSuppressed(new IOException("Не удалось восстановить исходную тему. Резервная копия: "
                            + backup.getAbsolutePath(), restore));
                }
                throw error;
            }
        } finally { if (!keepBackup) removeTemp(backup); }
    }

    private static void writeVerified(ContentResolver resolver, Uri uri, File file) throws Exception {
        try (InputStream input = new FileInputStream(file);
             OutputStream output = require(resolver.openOutputStream(uri, "wt"))) { input.transferTo(output); }
        try (InputStream input = new FileInputStream(file);
             InputStream written = require(resolver.openInputStream(uri))) {
            if (!MessageDigest.isEqual(digest(input), digest(written))) {
                throw new IOException("Записанный файл не прошёл проверку целостности.");
            }
        }
    }

    private static byte[] description(String title, String pack) throws IOException {
        StringWriter writer = new StringWriter();
        XmlSerializer xml = Xml.newSerializer();
        xml.setOutput(writer);
        xml.startDocument("UTF-8", true);
        xml.startTag(null, "HnTheme");
        String[][] fields = {{"title", title}, {"title-cn", title}, {"author", "Icon Foundry"},
                {"designer", "Icon Foundry"}, {"version", "12"}, {"font", "Default"}, {"font-cn", "Default"},
                {"briefInfo", "Icons from " + pack + "; personal use"}};
        for (String[] field : fields) xml.startTag(null, field[0]).text(field[1]).endTag(null, field[0]);
        xml.endTag(null, "HnTheme").endDocument();
        return writer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static List<String> coverIcons(IconRepository.Pack pack, List<IconRepository.App> apps,
                                   Map<String, String> overrides) {
        String[][] slots = {
                {"com.hihonor.contacts", "com.huawei.contacts", "com.google.android.contacts", "com.android.contacts"},
                {"com.hihonor.contacts", "com.huawei.contacts", "com.google.android.dialer", "com.android.dialer", "com.android.contacts"},
                {"com.hihonor.camera", "com.huawei.camera", "com.google.android.GoogleCamera", "com.android.camera", "com.android.camera2"},
                {"com.hihonor.photos", "com.hihonor.gallery", "com.huawei.photos", "com.android.gallery3d", "com.google.android.apps.photos"},
                {"com.google.android.apps.messaging", "com.android.mms", "com.hihonor.mms", "com.huawei.mms"},
                {"com.android.chrome", "com.brave.browser", "com.hihonor.browser", "com.huawei.browser", "com.android.browser", "org.mozilla.firefox"},
                {"com.android.settings"},
                {"com.hihonor.calendar", "com.huawei.calendar", "com.google.android.calendar", "com.android.calendar"},
                {"com.android.deskclock", "com.hihonor.deskclock", "com.huawei.deskclock", "com.google.android.deskclock"},
                {"com.hihonor.filemanager", "com.huawei.hidisk", "com.google.android.apps.nbu.files", "com.android.documentsui"},
                {"com.hihonor.hnmusicplayer", "com.hihonor.music", "com.huawei.music", "com.spotify.music", "com.google.android.apps.youtube.music"},
                {"com.hihonor.email", "com.android.email", "com.google.android.gm", "com.microsoft.office.outlook"}
        };
        List<String> result = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        java.util.Set<String> excluded = new java.util.HashSet<>();
        for (IconRepository.App app : apps) {
            if ("".equals(overrides.get(app.key()))) {
                String automatic = pack.automatic(app);
                if (automatic != null) excluded.add(IconRepository.resourceName(automatic));
            }
        }
        for (int slot = 0; slot < slots.length; slot++) {
            String selected = null;
            boolean explicit = false;
            for (String packageName : slots[slot]) {
                final int category = slot;
                List<IconRepository.App> candidates = apps.stream()
                        .filter(app -> app.component().getPackageName().equals(packageName))
                        .filter(app -> coverActivity(app.component(), category))
                        .sorted(java.util.Comparator.comparing(IconRepository.App::key)).toList();
                if (candidates.isEmpty()) continue;
                for (IconRepository.App candidate : candidates) {
                    if (overrides.containsKey(candidate.key())) {
                        selected = pack.selected(candidate, overrides);
                        explicit = true;
                        break;
                    }
                    selected = pack.automatic(candidate);
                    if (selected != null) break;
                }
                if (explicit || selected != null) break;
            }
            if (selected == null && !explicit) {
                if (slot < 2) {
                    String activity = "com.android.contacts.activities."
                            + (slot == 0 ? "PeopleActivity" : "DialtactsActivity");
                    selected = pack.mappings.get("com.android.contacts/" + activity);
                }
                if (selected == null) for (String packageName : slots[slot]) {
                    // Contacts and Phone share an OEM package, so its baseline cannot represent both.
                    if (slot == 1 && packageName.endsWith(".contacts")) continue;
                    selected = pack.byPackage.get(packageName);
                    if (selected != null) break;
                }
            }
            if (selected != null && !excluded.contains(IconRepository.resourceName(selected))
                    && used.add(IconRepository.resourceName(selected))) result.add(selected);
        }
        for (String name : pack.icons.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER
                .thenComparing(java.util.Comparator.naturalOrder())).toList()) {
            if (result.size() == 12) break;
            if (!excluded.contains(name) && used.add(name)) result.add(name);
        }
        return result;
    }

    private static boolean coverActivity(android.content.ComponentName component, int slot) {
        if (slot > 1 || !component.getPackageName().endsWith(".contacts")) return true;
        String name = component.getClassName().toLowerCase(Locale.ROOT);
        return slot == 0 ? name.endsWith(".peopleactivity") : name.endsWith(".dialtactsactivity");
    }

    static Bitmap cover(IconRepository.Pack pack, List<IconRepository.App> apps, Map<String, String> overrides)
            throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColor(Color.rgb(255, 193, 7));
        paint.setTextSize(42);
        canvas.drawText("ICON FOUNDRY", 50, 130, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(28);
        canvas.drawText("HONOR / " + pack.label, 50, 190, paint);
        int index = 0;
        try {
            for (String selected : coverIcons(pack, apps, overrides)) {
                Bitmap icon = pack.render(selected, 140);
                canvas.drawBitmap(icon, 60 + (index % 3) * 230, 290 + (index / 3) * 230, paint);
                icon.recycle();
                index++;
            }
            return bitmap;
        } catch (IOException | RuntimeException error) {
            bitmap.recycle();
            throw error;
        }
    }

    static Bitmap readCover(ContentResolver resolver, Uri uri) throws IOException {
        return readImage(resolver, uri, 1600);
    }

    static Bitmap readWallpaper(ContentResolver resolver, Uri uri) throws IOException {
        return readImage(resolver, uri, 3200);
    }

    private static Bitmap readImage(ContentResolver resolver, Uri uri, int maximum) throws IOException {
        android.graphics.ImageDecoder.Source source = android.graphics.ImageDecoder.createSource(resolver, uri);
        return android.graphics.ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            float scale = Math.min(1f, (float) maximum / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
        });
    }

    static Bitmap cropCover(Bitmap original, float centreX, float centreY, float zoom) {
        Bitmap cover = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cover);
        canvas.drawColor(Color.BLACK);
        float scale = Math.max(720f / original.getWidth(), 1280f / original.getHeight()) * zoom;
        float width = original.getWidth() * scale, height = original.getHeight() * scale;
        float left = Math.max(720 - width, Math.min(0, 360 - centreX * width));
        float top = Math.max(1280 - height, Math.min(0, 640 - centreY * height));
        android.graphics.RectF dest = new android.graphics.RectF(left, top, left + width, top + height);
        canvas.drawBitmap(original, null, dest, new Paint(Paint.FILTER_BITMAP_FLAG));
        return cover;
    }

    static void verifyArchive(File file, int targetCount, IconShape shape, boolean clearFolderBackground) throws IOException {
        verifyArchive(file, targetCount, shape, clearFolderBackground, IconShape.SIZE - 2 * IconShape.INSET);
    }

    static void verifyArchive(File file, int targetCount, IconShape shape, boolean clearFolderBackground,
                              int maskSize) throws IOException {
        verifyArchive(file, targetCount, shape, clearFolderBackground, maskSize, false);
    }

    static void verifyArchive(File file, int targetCount, IconShape shape, boolean clearFolderBackground,
                              int maskSize, boolean wallpaper) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.size() != (clearFolderBackground ? 7 : 6) + (wallpaper ? 3 : 0)
                    || zip.getEntry("description.xml") == null || zip.getEntry("icons") == null
                    || zip.getEntry("preview/cover.jpg") == null || zip.getEntry("preview/preview_icons_0.jpg") == null
                    || zip.getEntry("preview/icon_small.jpg") == null || zip.getEntry("preview/") == null) {
                throw new IOException("Неполный архив темы.");
            }
            if (wallpaper) {
                ZipEntry home = zip.getEntry("wallpaper/home_wallpaper_0.jpg");
                ZipEntry lock = zip.getEntry("wallpaper/unlock_wallpaper_0.jpg");
                if (home == null || lock == null || zip.getEntry("wallpaper/") == null) {
                    throw new IOException("В архиве отсутствуют выбранные обои.");
                }
                byte[] homeBytes, lockBytes;
                try (InputStream input = zip.getInputStream(home)) { homeBytes = input.readAllBytes(); }
                try (InputStream input = zip.getInputStream(lock)) { lockBytes = input.readAllBytes(); }
                verifyWallpaper(homeBytes);
                if (!java.util.Arrays.equals(homeBytes, lockBytes)) throw new IOException("Обои рабочего стола и блокировки различаются.");
            } else if (zip.stream().anyMatch(entry -> entry.getName().startsWith("wallpaper/"))) {
                throw new IOException("В архиве появились невыбранные обои.");
            }
            ZipEntry launcher = zip.getEntry(LAUNCHER);
            if ((launcher != null) != clearFolderBackground) {
                throw new IOException("Некорректная настройка фона миниатюр папки.");
            }
            if (launcher != null) {
                try (InputStream input = zip.getInputStream(launcher);
                     var module = new java.util.zip.ZipInputStream(input)) {
                    java.util.Set<String> expected = new java.util.HashSet<>(List.of("theme.xml", "night/theme.xml"));
                    for (ZipEntry entry; (entry = module.getNextEntry()) != null;) {
                        if (!expected.remove(entry.getName()) || !FOLDER_COLORS.equals(
                                new String(module.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8))) {
                            throw new IOException("Некорректный модуль фона миниатюр папки.");
                        }
                    }
                    if (!expected.isEmpty()) throw new IOException("Неполный модуль фона миниатюр папки.");
                }
            }
            try (var nested = new java.util.zip.ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                int count = 0;
                boolean hasMask = false;
                byte[] buffer = new byte[8192];
                for (ZipEntry entry; (entry = nested.getNextEntry()) != null;) {
                    String name = entry.getName();
                    if (name.equals("icon_mask.png")) {
                        if (hasMask || shape == IconShape.NONE) throw new IOException("Неожиданная маска иконок.");
                        byte[] bytes = nested.readNBytes(1024 * 1024);
                        Bitmap mask = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (mask == null) throw new IOException("Маска иконок не читается.");
                        try {
                            if (mask.getWidth() != IconShape.SIZE || mask.getHeight() != IconShape.SIZE) {
                                throw new IOException("Некорректный размер маски.");
                            }
                            Bitmap expected = shape.mask(maskSize);
                            try {
                                if (!mask.sameAs(expected)) throw new IOException("Некорректная форма маски.");
                            } finally { expected.recycle(); }
                        } finally { mask.recycle(); }
                        hasMask = true;
                        continue;
                    }
                    if (!name.endsWith(".png") || name.contains("/")
                            || !IconRepository.safeName(name.substring(0, name.length() - 4))) {
                        throw new IOException("Недопустимый путь иконки: " + name);
                    }
                    while (nested.read(buffer) != -1) { /* Validate CRC while streaming. */ }
                    count++;
                }
                if (count != targetCount) throw new IOException("Неполный архив иконок.");
                if (hasMask != (shape != IconShape.NONE)) throw new IOException("В архиве отсутствует выбранная маска.");
            }
        }
    }

    private static void verifyWallpaper(byte[] bytes) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth < 1 || options.outHeight < 1 || !"image/jpeg".equals(options.outMimeType)) {
            throw new IOException("Обои не читаются. Выберите изображение заново.");
        }
    }

    static String documentName(ContentResolver resolver, Uri uri) throws IOException {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath()).getName();
        try (Cursor cursor = resolver.query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("Не удалось прочитать имя документа.");
            return cursor.getString(0);
        }
    }

    private static IOException invalidTemplate(Context context) { return new IOException(context.getString(R.string.template_error)); }
    private static <T> T require(T object) throws IOException {
        if (object == null) throw new IOException("Хранилище не открыло поток.");
        return object;
    }
    private static byte[] png(Bitmap bitmap) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new IOException("Ошибка кодирования PNG.");
        return bytes.toByteArray();
    }
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    private static byte[] digest(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        return digest.digest();
    }
    private static void copyLimited(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            total += count;
            if (total > limit) throw new IOException("Размер данных образца превышает допустимый предел.");
            output.write(buffer, 0, count);
        }
    }
    private static void removeTemp(File file) {
        if (file.exists() && !file.delete()) android.util.Log.w("IconManager", "Не удалён временный файл " + file);
    }
}
