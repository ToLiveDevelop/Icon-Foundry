package dev.iconmanager;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

final class Draft {
    String title = "";
    String pack = "";
    String tree = "";
    String coverPath = "";
    String wallpaperPath = "";
    String templateId = "";
    boolean fullPack;
    boolean clearFolderBackground = true;
    IconShape shape = IconShape.NONE;
    final Map<String, String> overrides = new LinkedHashMap<>();

    static Draft load(Context context) throws JSONException {
        Draft d = new Draft();
        String saved = context.getSharedPreferences("draft", 0).getString("json", "");
        if (saved.isEmpty()) return d;
        return fromJson(new JSONObject(saved));
    }

    static Draft fromJson(JSONObject j) throws JSONException {
        Draft d = new Draft();
        d.title = j.optString("title");
        d.pack = j.optString("pack");
        d.tree = j.optString("tree");
        d.coverPath = j.optString("coverPath");
        d.wallpaperPath = j.optString("wallpaperPath");
        d.templateId = j.optString("templateId");
        d.fullPack = j.optBoolean("fullPack");
        d.clearFolderBackground = j.optBoolean("clearFolderBackground", true);
        try {
            d.shape = IconShape.valueOf(j.optString("shape", IconShape.NONE.name()));
        } catch (IllegalArgumentException error) {
            throw new JSONException("Неизвестная сохранённая форма иконки: " + j.optString("shape"), error);
        }
        JSONObject overrides = j.optJSONObject("overrides");
        if (overrides != null) {
            for (var keys = overrides.keys(); keys.hasNext();) {
                String key = keys.next();
                String value = overrides.getString(key);
                if (!value.isEmpty() && !d.pack.isEmpty() && !value.contains(":")) {
                    value = IconRepository.reference(d.pack, value);
                }
                d.overrides.put(key, value);
            }
        }
        return d;
    }

    void save(Context context) throws JSONException {
        context.getSharedPreferences("draft", 0).edit().putString("json", toJson().toString()).apply();
    }

    void markSaved(Context context) throws Exception {
        context.getSharedPreferences("draft", 0).edit().putString("savedFingerprint", fingerprint(context)).apply();
    }

    boolean hasUnsavedChanges(Context context) {
        if (pack.isEmpty() && title.isEmpty() && overrides.isEmpty() && coverPath.isEmpty() && wallpaperPath.isEmpty()) {
            return false;
        }
        if (templateId.isEmpty()) return true;
        try {
            return !fingerprint(context).equals(context.getSharedPreferences("draft", 0).getString("savedFingerprint", ""));
        } catch (Exception error) {
            return true;
        }
    }

    private String fingerprint(Context context) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        JSONObject canonical = toJson().put("overrides", new JSONObject(new java.util.TreeMap<>(overrides)));
        digest.update(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String path : new String[]{coverPath, wallpaperPath}) {
            digest.update((byte) 0);
            if (!path.isEmpty()) {
                java.io.File file = new java.io.File(context.getFilesDir(), path);
                if (file.isFile()) digest.update(java.nio.file.Files.readAllBytes(file.toPath()));
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return hex.toString();
    }

    JSONObject toJson() throws JSONException {
        JSONObject j = new JSONObject();
        j.put("title", title).put("pack", pack).put("tree", tree)
                .put("coverPath", coverPath)
                .put("wallpaperPath", wallpaperPath)
                .put("templateId", templateId)
                .put("fullPack", fullPack)
                .put("clearFolderBackground", clearFolderBackground)
                .put("shape", shape.name())
                .put("overrides", new JSONObject(overrides));
        return j;
    }
}
