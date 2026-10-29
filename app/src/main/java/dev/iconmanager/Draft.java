package dev.iconmanager;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

final class Draft {
    String title = "";
    String filename = "";
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
        d.filename = j.optString("filename");
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

    JSONObject toJson() throws JSONException {
        JSONObject j = new JSONObject();
        j.put("title", title).put("filename", filename).put("pack", pack).put("tree", tree)
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
