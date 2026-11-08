package dev.iconmanager;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Draft {
    String title = "";
    /** Base packs in priority order; the first one wins. */
    final List<String> packs = new ArrayList<>();
    final Map<String, Integer> exportSizes = new LinkedHashMap<>();
    String tree = "";
    String coverPath = "";
    String wallpaperPath = "";
    String templateId = "";
    boolean fullPack;
    boolean clearFolderBackground = true;
    boolean systemIcons;
    boolean hideVolte;
    String overlay = "";
    boolean overlayAll;
    int overlayScale = 100;
    boolean overlayClip = true;
    String background = "";
    boolean backgroundAll;
    int backgroundScale = 100;
    boolean backgroundClip = true;
    IconShape shape = IconShape.ROUNDED_SQUARE;
    int[] corners = IconShape.DEFAULT_CORNERS.clone();
    String shapeFile = "";
    int maskScale = 100;
    int iconScale = 100;
    final List<IconFilters.Filter> filters = new ArrayList<>();
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
        JSONArray packs = j.optJSONArray("packs");
        if (packs != null) for (int i = 0; i < packs.length(); i++) d.addPack(packs.getString(i));
        else if (!j.optString("pack").isEmpty()) d.addPack(j.optString("pack"));
        JSONObject sizes = j.has("exportSizes") ? j.getJSONObject("exportSizes") : null;
        if (sizes != null) for (var keys = sizes.keys(); keys.hasNext();) {
            String key = keys.next();
            Object value = sizes.get(key);
            if (!(value instanceof Integer size) || !IconRepository.safeName(key) || !ExportSizes.valid(size)) {
                throw new JSONException(App.text(R.string.export_size_invalid));
            }
            d.exportSizes.put(key, size);
        }
        d.tree = j.optString("tree");
        d.coverPath = j.optString("coverPath");
        d.wallpaperPath = j.optString("wallpaperPath");
        d.templateId = j.optString("templateId");
        d.fullPack = j.optBoolean("fullPack");
        d.clearFolderBackground = j.optBoolean("clearFolderBackground", true);
        d.systemIcons = j.optBoolean("systemIcons");
        d.hideVolte = j.optBoolean("hideVolte");
        d.overlay = j.optString("overlay");
        if (!d.overlay.isEmpty() && !FileIcons.isFile(d.overlay)) throw new JSONException(App.text(R.string.overlay_invalid));
        d.overlayAll = j.optBoolean("overlayAll");
        d.overlayScale = j.optInt("overlayScale", 100);
        if (d.overlayScale < IconOverlay.MIN_SCALE || d.overlayScale > IconOverlay.MAX_SCALE) {
            throw new JSONException(App.text(R.string.overlay_scale_invalid));
        }
        d.overlayClip = j.optBoolean("overlayClip", true);
        d.background = j.optString("background");
        if (!d.background.isEmpty() && !FileIcons.isFile(d.background)) {
            throw new JSONException(App.text(R.string.background_invalid));
        }
        d.backgroundAll = j.optBoolean("backgroundAll");
        d.backgroundScale = j.optInt("backgroundScale", 100);
        if (d.backgroundScale < IconOverlay.MIN_SCALE || d.backgroundScale > IconOverlay.MAX_SCALE) {
            throw new JSONException(App.text(R.string.background_scale_invalid));
        }
        d.backgroundClip = j.optBoolean("backgroundClip", true);
        JSONArray corners = j.optJSONArray("corners");
        if (corners != null) {
            if (corners.length() != 4) throw new JSONException(App.text(R.string.corners_invalid));
            int[] values = new int[4];
            for (int i = 0; i < 4; i++) values[i] = corners.getInt(i);
            try { d.corners = IconShape.validCorners(values); }
            catch (IllegalArgumentException error) { throw new JSONException(error.getMessage()); }
        }
        try {
            d.shape = IconShape.valueOf(j.optString("shape", IconShape.NONE.name()));
        } catch (IllegalArgumentException error) {
            throw new JSONException(App.text(R.string.saved_shape_error) + j.optString("shape"), error);
        }
        d.shapeFile = j.optString("shapeFile");
        if (d.shape == IconShape.FILE ? !FileIcons.isFile(d.shapeFile) : !d.shapeFile.isEmpty()) {
            throw new JSONException(App.text(R.string.mask_file_invalid));
        }
        d.maskScale = j.optInt("maskScale", 100);
        if (d.maskScale < IconOverlay.MIN_SCALE || d.maskScale > IconOverlay.MAX_SCALE
                || d.shape != IconShape.FILE && d.maskScale != 100) {
            throw new JSONException(App.text(R.string.mask_scale_invalid));
        }
        d.iconScale = j.optInt("iconScale", 100);
        if (d.iconScale < IconOverlay.MIN_SCALE || d.iconScale > IconOverlay.MAX_SCALE) {
            throw new JSONException(App.text(R.string.icon_scale_invalid));
        }
        d.filters.addAll(IconFilters.fromJson(j.optJSONArray("iconFilters")));
        JSONObject overrides = j.optJSONObject("overrides");
        if (overrides != null) {
            for (var keys = overrides.keys(); keys.hasNext();) {
                String key = keys.next();
                String value = overrides.getString(key);
                if (!value.isEmpty() && !d.packs.isEmpty() && !value.contains(":")) {
                                    value = IconRepository.reference(d.primaryPack(), value);
                }
                d.overrides.put(key, value);
            }
            // Preserve assignments made before the Parallel Space package key was corrected.
            String legacyKey = "com.hihonor.pallarspace/com.hihonor.parallelspace.launcher.InitActivity";
            if (d.overrides.containsKey(legacyKey)) {
                String correctedKey = new android.content.ComponentName(IconRepository.PARALLEL_SPACE,
                        IconRepository.PARALLEL_SPACE + ".launcher.InitActivity").flattenToString();
                d.overrides.putIfAbsent(correctedKey, d.overrides.remove(legacyKey));
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
        if (packs.isEmpty() && title.isEmpty() && overrides.isEmpty() && coverPath.isEmpty()
                && wallpaperPath.isEmpty() && overlay.isEmpty() && background.isEmpty() && shapeFile.isEmpty()
                && !hideVolte) {
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
            if (!overlay.isEmpty()) digest.update(FileIcons.bytes(context, overlay));
            if (!background.isEmpty()) digest.update(FileIcons.bytes(context, background));
        }
        if (!shapeFile.isEmpty()) digest.update(FileIcons.bytes(context, shapeFile));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return hex.toString();
    }

    JSONObject toJson() throws JSONException {
        JSONObject j = new JSONObject();
        j.put("title", title).put("pack", primaryPack()).put("tree", tree)
                .put("coverPath", coverPath)
                .put("wallpaperPath", wallpaperPath)
                .put("templateId", templateId)
                .put("fullPack", fullPack)
                .put("clearFolderBackground", clearFolderBackground)
                .put("shape", shape.name())
                .put("overrides", new JSONObject(overrides));
        // A single pack keeps the original format, so older templates and fingerprints stay identical.
        if (packs.size() > 1) j.put("packs", new JSONArray(packs));
        if (!exportSizes.isEmpty()) j.put("exportSizes", new JSONObject(new java.util.TreeMap<>(exportSizes)));
        if (systemIcons) j.put("systemIcons", true);
        if (hideVolte) j.put("hideVolte", true);
        if (!overlay.isEmpty()) j.put("overlay", overlay).put("overlayAll", overlayAll);
        if (!overlay.isEmpty() && overlayScale != 100) j.put("overlayScale", overlayScale);
        if (!overlay.isEmpty() && !overlayClip) j.put("overlayClip", false);
        if (!background.isEmpty()) {
            j.put("background", background).put("backgroundAll", backgroundAll);
            if (backgroundScale != 100) j.put("backgroundScale", backgroundScale);
            if (!backgroundClip) j.put("backgroundClip", false);
        }
        if (shape == IconShape.CUSTOM) {
            JSONArray values = new JSONArray();
            for (int corner : corners) values.put(corner);
            j.put("corners", values);
        }
        if (shape == IconShape.FILE) {
            if (!FileIcons.isFile(shapeFile)) throw new JSONException(App.text(R.string.mask_file_invalid));
            j.put("shapeFile", shapeFile);
            if (maskScale != 100) j.put("maskScale", maskScale);
        }
        if (iconScale != 100) j.put("iconScale", iconScale);
        if (!filters.isEmpty()) j.put("iconFilters", IconFilters.toJson(filters));
        return j;
    }

    String primaryPack() { return packs.isEmpty() ? "" : packs.get(0); }

    List<String> fileReferences() {
        List<String> references = new ArrayList<>(overrides.values());
        if (!overlay.isEmpty()) references.add(overlay);
        if (!background.isEmpty()) references.add(background);
        if (!shapeFile.isEmpty()) references.add(shapeFile);
        return references;
    }

    private void addPack(String packageName) throws JSONException {
        if (!IconRepository.safeName(packageName)) throw new JSONException(App.text(R.string.saved_pack_error) + packageName);
        if (packs.contains(packageName)) return;
        if (packs.size() == IconRepository.MAX_PACKS) {
            throw new JSONException(App.text(R.string.pack_limit_prefix) + IconRepository.MAX_PACKS + App.text(R.string.pack_limit_suffix));
        }
        packs.add(packageName);
    }
}
