package dev.iconmanager;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class TemplateStore {
    static final int MAX_BACKUP_BYTES = 64 * 1024 * 1024;
    private static final String BACKUP_FORMAT = "icon-foundry-template-backup";
    private static final int BACKUP_VERSION = 1;

    record Entry(String id, String packLabel, Instant savedAt, JSONObject settings, String cover, String wallpaper,
                 JSONObject fileIcons) {
        String name() {
            return packLabel + " · " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
                    .withZone(ZoneId.systemDefault()).format(savedAt);
        }
    }

    record MergeResult(int imported, int retained) {}

    private final Context context;
    private final AtomicFile file;

    TemplateStore(Context context) {
        this.context = context;
        file = new AtomicFile(new File(context.getFilesDir(), "templates.json"));
    }

    List<Entry> list() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return new ArrayList<>();
        JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        if (root.getInt("version") != 1) throw new IOException(App.text(R.string.template_version_error));
        JSONArray values = root.getJSONArray("templates");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.getJSONObject(i);
            entries.add(new Entry(item.getString("id"), item.getString("packLabel"),
                    Instant.parse(item.getString("savedAt")), item.getJSONObject("settings"), item.getString("cover"),
                    item.optString("wallpaper"), item.optJSONObject("fileIcons") == null ? new JSONObject()
                            : item.getJSONObject("fileIcons")));
        }
        entries.sort(Comparator.comparing(Entry::savedAt).reversed());
        return entries;
    }

    Entry save(Draft draft, String packLabel, boolean asNew) throws Exception {
        List<Entry> entries = list();
        String id = asNew || draft.templateId.isEmpty() ? UUID.randomUUID().toString() : draft.templateId;
        if (!asNew && !draft.templateId.isEmpty() && entries.stream().noneMatch(entry -> entry.id().equals(id))) {
            throw new IOException(App.text(R.string.template_gone));
        }
        JSONObject settings = draft.toJson();
        settings.put("templateId", id).put("coverPath", "").put("wallpaperPath", "");
        String cover = "";
        if (!draft.coverPath.isEmpty()) {
            cover = Base64.encodeToString(Files.readAllBytes(new File(context.getFilesDir(), draft.coverPath).toPath()),
                    Base64.NO_WRAP);
        }
        String wallpaper = "";
        if (!draft.wallpaperPath.isEmpty()) {
            wallpaper = Base64.encodeToString(Files.readAllBytes(new File(context.getFilesDir(), draft.wallpaperPath).toPath()),
                    Base64.NO_WRAP);
        }
        JSONObject fileIcons = new JSONObject();
        for (String reference : draft.fileReferences()) {
            if (FileIcons.isFile(reference) && !fileIcons.has(reference)) {
                fileIcons.put(reference, Base64.encodeToString(FileIcons.bytes(context, reference), Base64.NO_WRAP));
            }
        }
        Entry saved = new Entry(id, packLabel, Instant.now(), settings, cover, wallpaper, fileIcons);
        entries.removeIf(entry -> entry.id().equals(id));
        entries.add(saved);
        write(entries);
        return saved;
    }

    void delete(String id) throws Exception {
        List<Entry> entries = list();
        if (!entries.removeIf(entry -> entry.id().equals(id))) throw new IOException(App.text(R.string.template_not_found));
        write(entries);
    }

    byte[] backup() throws Exception {
        List<Entry> entries = list();
        JSONArray values = new JSONArray();
        for (Entry entry : entries) {
            validateEntry(entry);
            JSONObject settings = new JSONObject(entry.settings().toString())
                    .put("tree", "").put("coverPath", "").put("wallpaperPath", "");
            values.put(toJson(new Entry(entry.id(), entry.packLabel(), entry.savedAt(), settings, entry.cover(),
                    entry.wallpaper(), entry.fileIcons())));
        }
        JSONObject backup = new JSONObject().put("format", BACKUP_FORMAT).put("version", BACKUP_VERSION)
                .put("templates", values).put("sha256", digest(values.toString().getBytes(StandardCharsets.UTF_8)));
        byte[] bytes = backup.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BACKUP_BYTES) throw new IOException(App.text(R.string.template_backup_too_large));
        return bytes;
    }

    MergeResult restoreBackup(byte[] bytes) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_BACKUP_BYTES) {
            throw new IOException(App.text(R.string.template_backup_invalid));
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException(App.text(R.string.template_backup_invalid), error);
        }

        JSONObject root;
        try {
            root = new JSONObject(json);
            requireKeys(root, Set.of("format", "version", "templates", "sha256"));
            Object version = root.get("version");
            if (!BACKUP_FORMAT.equals(root.getString("format"))
                    || !(version instanceof Integer number) || number != BACKUP_VERSION) {
                throw new IOException(App.text(R.string.template_backup_invalid));
            }
        } catch (org.json.JSONException error) {
            throw new IOException(App.text(R.string.template_backup_invalid), error);
        }

        JSONArray values;
        try {
            values = root.getJSONArray("templates");
            String expectedDigest = root.getString("sha256");
            String actualDigest = digest(values.toString().getBytes(StandardCharsets.UTF_8));
            if (!expectedDigest.matches("[0-9a-f]{64}") || !MessageDigest.isEqual(
                    expectedDigest.getBytes(StandardCharsets.US_ASCII), actualDigest.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException(App.text(R.string.template_backup_invalid));
            }
        } catch (org.json.JSONException error) {
            throw new IOException(App.text(R.string.template_backup_invalid), error);
        }

        List<Entry> incoming = new ArrayList<>();
        Set<String> importedIds = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            Entry entry;
            try {
                entry = fromBackupJson(values.getJSONObject(i));
                if (!importedIds.add(entry.id())) throw new IOException(App.text(R.string.template_backup_invalid));
                validateEntry(entry);
            } catch (org.json.JSONException | RuntimeException error) {
                throw new IOException(App.text(R.string.template_backup_invalid), error);
            }
            incoming.add(entry);
        }

        List<Entry> merged = list();
        Set<String> currentIds = new HashSet<>();
        for (Entry entry : merged) currentIds.add(entry.id());
        int retained = 0;
        for (Entry entry : incoming) {
            if (currentIds.add(entry.id())) merged.add(entry);
            else retained++;
        }
        int imported = incoming.size() - retained;
        if (imported > 0) write(merged);
        return new MergeResult(imported, retained);
    }

    private Entry fromBackupJson(JSONObject item) throws Exception {
        requireKeys(item, Set.of("id", "packLabel", "savedAt", "settings", "cover", "wallpaper", "fileIcons"));
        String id = item.getString("id");
        if (!UUID.fromString(id).toString().equals(id)) throw new IOException(App.text(R.string.template_backup_invalid));
        JSONObject settings = item.getJSONObject("settings");
        Draft draft = Draft.fromJson(settings);
        if (!id.equals(draft.templateId)) throw new IOException(App.text(R.string.template_backup_invalid));
        draft.tree = "";
        draft.coverPath = "";
        draft.wallpaperPath = "";
        return new Entry(id, item.getString("packLabel"), Instant.parse(item.getString("savedAt")), draft.toJson(),
                item.getString("cover"), item.getString("wallpaper"), item.getJSONObject("fileIcons"));
    }

    private static void validateEntry(Entry entry) throws Exception {
        if (!UUID.fromString(entry.id()).toString().equals(entry.id()) || entry.packLabel().isBlank()
                || entry.packLabel().length() > 1024) throw new IOException(App.text(R.string.template_backup_invalid));
        Draft draft = Draft.fromJson(entry.settings());
        if (!entry.id().equals(draft.templateId)) throw new IOException(App.text(R.string.template_backup_invalid));
        Set<String> referencedFiles = new HashSet<>();
        for (String reference : draft.fileReferences()) {
            if (FileIcons.isFile(reference)) referencedFiles.add(reference);
        }
        for (var keys = entry.fileIcons().keys(); keys.hasNext();) {
            String reference = keys.next();
            if (!referencedFiles.contains(reference)) throw new IOException(App.text(R.string.template_backup_invalid));
            byte[] image = decodeBase64(entry.fileIcons().getString(reference));
            FileIcons.validate(reference, image);
        }
        for (String reference : referencedFiles) {
            if (!entry.fileIcons().has(reference)) throw new IOException(App.text(R.string.template_icon_missing));
        }
        validateImageIfPresent(entry.cover());
        validateImageIfPresent(entry.wallpaper());
    }

    private static void validateImageIfPresent(String encoded) throws IOException {
        if (encoded.isEmpty()) return;
        byte[] bytes = decodeBase64(encoded);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        long pixels = (long) options.outWidth * options.outHeight;
        String mime = options.outMimeType;
        if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth > 8192 || options.outHeight > 8192
                || pixels > 32_000_000L || !("image/jpeg".equals(mime) || "image/png".equals(mime)
                || "image/webp".equals(mime))) {
            throw new IOException(App.text(R.string.template_backup_invalid));
        }
    }

    private static byte[] decodeBase64(String encoded) throws IOException {
        try {
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            if (!Base64.encodeToString(bytes, Base64.NO_WRAP).equals(encoded)) {
                throw new IOException(App.text(R.string.template_backup_invalid));
            }
            return bytes;
        } catch (IllegalArgumentException error) {
            throw new IOException(App.text(R.string.template_backup_invalid), error);
        }
    }

    private static JSONObject toJson(Entry entry) throws org.json.JSONException {
        return new JSONObject().put("id", entry.id()).put("packLabel", entry.packLabel())
                .put("savedAt", entry.savedAt().toString()).put("settings", entry.settings())
                .put("cover", entry.cover()).put("wallpaper", entry.wallpaper()).put("fileIcons", entry.fileIcons());
    }

    private static void requireKeys(JSONObject object, Set<String> expected) throws org.json.JSONException, IOException {
        Set<String> actual = new HashSet<>();
        for (var keys = object.keys(); keys.hasNext();) actual.add(keys.next());
        if (!actual.equals(expected)) throw new IOException(App.text(R.string.template_backup_invalid));
    }

    private static String digest(byte[] bytes) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private void write(List<Entry> entries) throws Exception {
        JSONArray values = new JSONArray();
        for (Entry entry : entries) {
            values.put(new JSONObject().put("id", entry.id()).put("packLabel", entry.packLabel())
                    .put("savedAt", entry.savedAt().toString()).put("settings", entry.settings()).put("cover", entry.cover())
                    .put("wallpaper", entry.wallpaper()).put("fileIcons", entry.fileIcons()));
        }
        byte[] bytes = new JSONObject().put("version", 1).put("templates", values).toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            file.finishWrite(output);
        } catch (Exception error) {
            file.failWrite(output);
            throw error;
        }
    }

    Draft open(String id) throws Exception {
        Entry entry = list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IOException(App.text(R.string.template_not_found)));
        Draft draft = Draft.fromJson(entry.settings());
        draft.templateId = entry.id();
        for (String reference : draft.fileReferences()) {
            if (FileIcons.isFile(reference)) {
                if (!entry.fileIcons().has(reference)) throw new IOException(App.text(R.string.template_icon_missing));
                FileIcons.restore(context, reference, Base64.decode(entry.fileIcons().getString(reference), Base64.DEFAULT));
            }
        }
        if (!entry.cover().isEmpty()) {
            byte[] image = Base64.decode(entry.cover(), Base64.DEFAULT);
            AtomicFile cover = new AtomicFile(new File(context.getFilesDir(), "template-cover.jpg"));
            FileOutputStream output = cover.startWrite();
            try {
                output.write(image);
                cover.finishWrite(output);
            } catch (Exception error) {
                cover.failWrite(output);
                throw error;
            }
            draft.coverPath = "template-cover.jpg";
        }
        if (!entry.wallpaper().isEmpty()) {
            AtomicFile wallpaper = new AtomicFile(new File(context.getFilesDir(), "template-wallpaper.jpg"));
            FileOutputStream output = wallpaper.startWrite();
            try {
                output.write(Base64.decode(entry.wallpaper(), Base64.DEFAULT));
                wallpaper.finishWrite(output);
            } catch (Exception error) {
                wallpaper.failWrite(output);
                throw error;
            }
            draft.wallpaperPath = "template-wallpaper.jpg";
        }
        return draft;
    }
}
