package dev.iconmanager;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class TemplateStore {
    record Entry(String id, String packLabel, Instant savedAt, JSONObject settings, String cover, String wallpaper,
                 JSONObject fileIcons) {
        String name() {
            return packLabel + " · " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
                    .withZone(ZoneId.systemDefault()).format(savedAt);
        }
    }

    private final Context context;
    private final AtomicFile file;

    TemplateStore(Context context) {
        this.context = context;
        file = new AtomicFile(new File(context.getFilesDir(), "templates.json"));
    }

    List<Entry> list() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return new ArrayList<>();
        JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        if (root.getInt("version") != 1) throw new IOException("Неизвестная версия хранилища шаблонов.");
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
            throw new IOException("Открытый шаблон больше не существует. Сохраните как новый.");
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
        for (String reference : draft.overrides.values()) {
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
        if (!entries.removeIf(entry -> entry.id().equals(id))) throw new IOException("Шаблон не найден.");
        write(entries);
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
                .orElseThrow(() -> new IOException("Шаблон не найден."));
        Draft draft = Draft.fromJson(entry.settings());
        draft.templateId = entry.id();
        for (String reference : draft.overrides.values()) {
            if (FileIcons.isFile(reference)) {
                if (!entry.fileIcons().has(reference)) throw new IOException("В шаблоне отсутствует иконка из файла.");
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
