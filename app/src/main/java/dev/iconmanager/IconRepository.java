package dev.iconmanager;

import android.content.ComponentName;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressLint("DiscouragedApi") // Names in a different installed APK cannot be resolved through this app's R class.
final class IconRepository {
    record PackInfo(String packageName, String label, Bitmap badge) {}
    record App(ComponentName component, String label) {
        String key() { return component.flattenToString(); }
    }
    record Icon(String name, String category, int id) {}

    /** Reads the launcher icon file from the app's own resources, before the applied HONOR theme masks it. */
    static Drawable unthemedIcon(PackageManager manager, ComponentName component)
            throws PackageManager.NameNotFoundException {
        var info = manager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0));
        int resource = info.getIconResource();
        if (resource != 0) {
            try {
                Resources resources = manager.getResourcesForApplication(info.applicationInfo);
                int density = android.util.DisplayMetrics.DENSITY_XXXHIGH;
                android.util.TypedValue value = new android.util.TypedValue();
                resources.getValueForDensity(resource, density, value, true);
                String file = value.string == null ? "" : value.string.toString();
                // Resources.getDrawable returns HONOR's themed bitmap for launcher icons; the raw file does not.
                if (file.endsWith(".xml")) {
                    try (XmlResourceParser parser = resources.getAssets().openXmlResourceParser(value.assetCookie, file)) {
                        Drawable icon = Drawable.createFromXml(resources, parser, null);
                        if (icon != null) return icon;
                    }
                } else if (!file.isEmpty()) {
                    try (InputStream input = resources.openRawResource(resource, value)) {
                        Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(input);
                        if (bitmap != null) return new android.graphics.drawable.BitmapDrawable(resources, bitmap);
                    }
                }
            } catch (Resources.NotFoundException | IOException | XmlPullParserException error) {
                android.util.Log.w("IconManager", "Unthemed icon unavailable for " + component, error);
            }
        }
        return manager.getActivityIcon(component);
    }

    static final class Pack {
        final String packageName;
        final String label;
        final Resources resources;
        final Bitmap badge;
        final PackageManager manager;
        final Map<String, String> mappings = new LinkedHashMap<>();
        final Map<String, String> byPackage = new LinkedHashMap<>();
        final Map<String, Icon> icons = new LinkedHashMap<>();
        final Set<String> unavailable = new LinkedHashSet<>();
        final List<Pack> members = new ArrayList<>();
        private Integer fallbackSize;
        java.io.File fileIcons;

        Pack(String packageName, String label, Resources resources, Bitmap badge, PackageManager manager) {
            this.packageName = packageName;
            this.label = label;
            this.resources = resources;
            this.badge = badge;
            this.manager = manager;
        }

        List<String> labels() {
            return members.isEmpty() ? List.of(label) : members.stream().map(member -> member.label).toList();
        }

        List<String> packageNames() {
            return members.isEmpty() ? List.of(packageName) : members.stream().map(member -> member.packageName).toList();
        }

        String automatic(App app) {
            if (!members.isEmpty()) {
                // Priority is per application: the first pack that themes it wins as a whole.
                for (Pack member : members) {
                    String name = member.automatic(app);
                    if (name != null && safeName(name)) return reference(member.packageName, name);
                }
                return null;
            }
            String name = null;
            // HONOR retained Android's Contacts activities, but some packs mislabel their OEM entries.
            if (isLegacyContacts(app.component)) {
                name = mappings.get(new ComponentName("com.android.contacts",
                        app.component.getClassName()).flattenToString());
            }
            if (name == null) name = mappings.get(app.key());
            if (name == null) name = byPackage.get(app.component.getPackageName());
            return name != null && icons.containsKey(name) ? name : null;
        }

        static boolean isLegacyContacts(ComponentName component) {
            String pkg = component.getPackageName(), cls = component.getClassName();
            return (pkg.equals("com.hihonor.contacts") || pkg.equals("com.huawei.contacts"))
                    && (cls.equals("com.android.contacts.activities.PeopleActivity")
                    || cls.equals("com.android.contacts.activities.DialtactsActivity"));
        }

        String selected(App app, Map<String, String> overrides) {
            if (overrides.containsKey(app.key())) {
                String value = overrides.get(app.key());
                return value.isEmpty() ? null : value;
            }
            return automatic(app);
        }

        synchronized int fallbackSize() throws IOException {
            if (fallbackSize != null) return fallbackSize;
            List<String> names = (mappings.isEmpty() ? icons.keySet().stream() : mappings.values().stream())
                    .distinct().sorted().toList();
            List<Integer> sizes = new ArrayList<>();
            int samples = Math.min(48, names.size());
            for (int i = 0; i < samples; i++) {
                Bitmap bitmap = render(names.get(i * names.size() / samples), IconShape.SIZE);
                try {
                    android.graphics.Rect bounds = visibleBounds(bitmap);
                    if (!bounds.isEmpty()) sizes.add(Math.max(bounds.width(), bounds.height()));
                } finally { bitmap.recycle(); }
            }
            if (sizes.isEmpty()) throw new IOException(dev.iconmanager.App.text(R.string.pack_size_error));
            sizes.sort(Integer::compare);
            fallbackSize = sizes.get(sizes.size() / 2);
            return fallbackSize;
        }

        static android.graphics.Rect visibleBounds(Bitmap bitmap) {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            int left = width, top = height, right = -1, bottom = -1;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                // Ignore shadows and antialiasing rather than measuring the transparent canvas.
                if (android.graphics.Color.alpha(pixels[y * width + x]) < 128) continue;
                left = Math.min(left, x); top = Math.min(top, y);
                right = Math.max(right, x); bottom = Math.max(bottom, y);
            }
            return right < left ? new android.graphics.Rect() : new android.graphics.Rect(left, top, right + 1, bottom + 1);
        }

        Bitmap render(String name, int size) throws IOException {
            if (FileIcons.isFile(name)) return FileIcons.render(fileIcons, name, size);
            if (!members.isEmpty()) {
                String owner = sourcePackage(name, packageName);
                for (Pack member : members) {
                    if (member.packageName.equals(owner)) return member.render(resourceName(name), size);
                }
                return members.get(0).render(name, size);
            }
            String source = sourcePackage(name, packageName);
            String resource = resourceName(name);
            Resources sourceResources = resources;
            int resourceId;
            if (source.equals(packageName)) {
                Icon icon = icons.get(resource);
                resourceId = icon == null ? resources.getIdentifier(resource, "drawable", source) : icon.id();
                if (resourceId == 0) resourceId = resources.getIdentifier(resource, "mipmap", source);
                if (resourceId == 0) throw new IOException(dev.iconmanager.App.text(R.string.pack_resource_prefix) + label + dev.iconmanager.App.text(R.string.pack_resource_missing) + resource
                        + dev.iconmanager.App.text(R.string.choose_replacement));
            } else {
                try { sourceResources = manager.getResourcesForApplication(source); }
                catch (PackageManager.NameNotFoundException error) {
                    throw new IOException(dev.iconmanager.App.text(R.string.pack_missing_prefix) + source + dev.iconmanager.App.text(R.string.pack_missing_help), error);
                }
                resourceId = sourceResources.getIdentifier(resource, "drawable", source);
                if (resourceId == 0) resourceId = sourceResources.getIdentifier(resource, "mipmap", source);
                if (resourceId == 0) throw new IOException(dev.iconmanager.App.text(R.string.pack_resource_prefix) + source + dev.iconmanager.App.text(R.string.pack_resource_missing) + resource
                        + dev.iconmanager.App.text(R.string.choose_replacement));
            }
            Drawable drawable;
            try {
                drawable = sourceResources.getDrawableForDensity(resourceId, 0, null).mutate();
            } catch (Resources.NotFoundException e) {
                throw new IOException(dev.iconmanager.App.text(R.string.icon_resource_error) + name, e);
            }
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            float scale = Math.min((float) size / width, (float) size / height);
            int w = Math.max(1, Math.round(width * scale));
            int h = Math.max(1, Math.round(height * scale));
            drawable.setBounds((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2);
            drawable.draw(new Canvas(bitmap));
            return bitmap;
        }
    }

    static final int MAX_PACKS = 10;

    /** Merges packs by priority; a single pack is returned unchanged. */
    static Pack combine(List<Pack> packs) {
        if (packs.isEmpty()) return null;
        if (packs.size() == 1) return packs.get(0);
        if (packs.size() > MAX_PACKS) throw new IllegalArgumentException(dev.iconmanager.App.text(R.string.pack_limit_prefix) + MAX_PACKS + dev.iconmanager.App.text(R.string.pack_limit_suffix));
        Pack first = packs.get(0);
        Pack combined = new Pack(first.packageName, String.join(" + ", packs.stream().map(pack -> pack.label).toList()),
                first.resources, first.badge, first.manager);
        combined.fileIcons = first.fileIcons;
        combined.members.addAll(packs);
        for (Pack member : packs) {
            for (var entry : member.mappings.entrySet()) if (safeName(entry.getValue())) {
                combined.mappings.putIfAbsent(entry.getKey(), reference(member.packageName, entry.getValue()));
            }
            for (var entry : member.byPackage.entrySet()) if (safeName(entry.getValue())) {
                combined.byPackage.putIfAbsent(entry.getKey(), reference(member.packageName, entry.getValue()));
            }
            for (Icon icon : member.icons.values()) if (safeName(icon.name())) {
                String name = reference(member.packageName, icon.name());
                combined.icons.put(name, new Icon(name, icon.category(), icon.id()));
            }
        }
        return combined;
    }

    private final PackageManager pm;
    private final java.io.File fileIcons;

    IconRepository(Context context) {
        pm = context.getPackageManager();
        fileIcons = FileIcons.directory(context);
    }

    List<App> apps() {
        List<App> apps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo resolve : pm.queryIntentActivities(launcher, 0)) {
            ComponentName name = new ComponentName(resolve.activityInfo.packageName, resolve.activityInfo.name);
            if (seen.add(name.flattenToString())) {
                apps.add(new App(name, resolve.loadLabel(pm).toString()));
            }
        }
        apps.sort(Comparator.comparing(App::label, String.CASE_INSENSITIVE_ORDER).thenComparing(App::key));
        return apps;
    }

    List<PackInfo> packs() throws PackageManager.NameNotFoundException {
        Set<String> launchers = new LinkedHashSet<>();
        for (ResolveInfo info : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            launchers.add(info.activityInfo.packageName);
        }
        Set<String> candidates = new LinkedHashSet<>();
        List<Intent> intents = List.of(
                new Intent("org.adw.launcher.THEMES"),
                new Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
                new Intent("com.gau.go.launcherex.theme"),
                new Intent("ch.deletescape.lawnchair.ICONPACK"),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        for (Intent intent : intents) {
            for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
                candidates.add(info.activityInfo.packageName);
            }
        }
        List<PackInfo> result = new ArrayList<>();
        for (String packageName : candidates) {
            if (launchers.contains(packageName)) continue;
            Resources resources = pm.getResourcesForApplication(packageName);
            try {
                if (!hasXml(resources, packageName, "appfilter")) continue;
                boolean[] mapped = {false};
                parse(resources, packageName, "appfilter", parser -> {
                    if (mapped[0]) return;
                    String tag = parser.getName();
                    if ((!tag.equals("item") && !tag.equals("calendar"))
                            || component(parser.getAttributeValue(null, "component")) == null) return;
                    String name = tag.equals("calendar")
                            ? parser.getAttributeValue(null, "prefix") : parser.getAttributeValue(null, "drawable");
                    if (name == null) return;
                    if (tag.equals("calendar")) name += Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
                    mapped[0] = safeName(name) && (resources.getIdentifier(name, "drawable", packageName) != 0
                            || resources.getIdentifier(name, "mipmap", packageName) != 0);
                }, () -> {}, () -> mapped[0]);
                if (!mapped[0]) continue;
                String label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
                result.add(new PackInfo(packageName, label, badge(packageName)));
            } catch (IOException | XmlPullParserException | Resources.NotFoundException error) {
                android.util.Log.w("IconManager", "Invalid icon pack filter in " + packageName, error);
            }
        }
        result.sort(Comparator.comparing(PackInfo::label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    Pack load(String packageName) throws PackageManager.NameNotFoundException, IOException, XmlPullParserException {
        Resources resources = pm.getResourcesForApplication(packageName);
        Pack pack = new Pack(packageName,
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString(), resources, badge(packageName), pm);
        pack.fileIcons = fileIcons;
        parse(resources, packageName, "appfilter", parser -> {
            String tag = parser.getName();
            if (!tag.equals("item") && !tag.equals("calendar")) return;
            String raw = parser.getAttributeValue(null, "component");
            ComponentName component = component(raw);
            if (component == null) return;
            String drawable = parser.getAttributeValue(null, "drawable");
            if (tag.equals("calendar")) {
                String prefix = parser.getAttributeValue(null, "prefix");
                if (prefix == null) return;
                drawable = prefix + Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
            }
            if (drawable == null || drawable.isBlank()) return;
            if (addIcon(pack, drawable, dev.iconmanager.App.text(R.string.from_appfilter))) {
                pack.mappings.putIfAbsent(component.flattenToString(), drawable);
                pack.byPackage.putIfAbsent(component.getPackageName(), drawable);
            }
        }, () -> {}, () -> !pack.mappings.isEmpty());
        String[] category = {dev.iconmanager.App.text(R.string.category_all)};
        java.util.Set<String> catalogued = new java.util.HashSet<>();
        // Some packs expose no catalogue; their mapped resources remain selectable.
        if (hasXml(resources, packageName, "drawable")) {
            parse(resources, packageName, "drawable", parser -> {
                if (parser.getName().equals("category")) {
                    category[0] = parser.getAttributeValue(null, "title");
                    if (category[0] == null) category[0] = dev.iconmanager.App.text(R.string.category_all);
                } else if (parser.getName().equals("item")) {
                    String name = parser.getAttributeValue(null, "drawable");
                    if (name != null && !catalogued.contains(name) && addIcon(pack, name, category[0])) {
                        catalogued.add(name);
                    }
                }
            }, () -> category[0] = dev.iconmanager.App.text(R.string.category_all), () -> !catalogued.isEmpty());
        }
        if (pack.icons.isEmpty()) throw new IOException(dev.iconmanager.App.text(R.string.pack_empty));
        return pack;
    }

    private Bitmap badge(String packageName) throws PackageManager.NameNotFoundException {
        Drawable drawable = pm.getApplicationIcon(packageName).mutate();
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 96, 96);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    static ComponentName component(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("ComponentInfo{") && value.endsWith("}")) {
            value = value.substring(14, value.length() - 1);
        }
        ComponentName name = ComponentName.unflattenFromString(value);
        if (name == null || !safeName(name.getPackageName()) || !safeName(name.getClassName())) return null;
        return name;
    }

    static boolean safeName(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_.$]*") && !name.contains("..");
    }

    static String reference(String packageName, String resource) {
        if (!safeName(packageName) || !safeName(resource)) throw new IllegalArgumentException(dev.iconmanager.App.text(R.string.reference_invalid));
        return packageName + ":" + resource;
    }

    static String sourcePackage(String reference, String base) {
        int separator = reference.indexOf(':');
        return separator < 0 ? base : reference.substring(0, separator);
    }

    static String resourceName(String reference) {
        return reference.substring(reference.indexOf(':') + 1);
    }

    private static boolean addIcon(Pack pack, String name, String category) {
        int id = pack.resources.getIdentifier(name, "drawable", pack.packageName);
        if (id == 0) id = pack.resources.getIdentifier(name, "mipmap", pack.packageName);
        if (id == 0) {
            pack.unavailable.add(name);
            return false;
        }
        pack.unavailable.remove(name);
        pack.icons.put(name, new Icon(name, category, id));
        return true;
    }

    private static boolean hasXml(Resources resources, String packageName, String name) throws IOException {
        if (resources.getIdentifier(name, "xml", packageName) != 0) return true;
        for (String asset : resources.getAssets().list("")) {
            if (asset.equals(name + ".xml")) return true;
        }
        return false;
    }

    interface TagReader { void read(XmlPullParser parser); }

    private static void parse(Resources resources, String packageName, String name, TagReader reader)
            throws IOException, XmlPullParserException {
        parse(resources, packageName, name, reader, () -> {}, () -> true);
    }

    /** Prefers res/xml; assets are a fallback because some packs ship template stubs there. */
    private static void parse(Resources resources, String packageName, String name, TagReader reader,
                              Runnable betweenSources, java.util.function.BooleanSupplier usable)
            throws IOException, XmlPullParserException {
        boolean asset = false;
        for (String file : resources.getAssets().list("")) {
            if (file.equals(name + ".xml")) asset = true;
        }
        int id = resources.getIdentifier(name, "xml", packageName);
        if (id == 0 && !asset) throw new IOException(dev.iconmanager.App.text(R.string.xml_missing_prefix) + name + dev.iconmanager.App.text(R.string.xml_missing_in) + packageName);
        if (id != 0) {
            try (XmlResourceParser parser = resources.getXml(id)) {
                readTags(parser, reader);
            }
            if (usable.getAsBoolean()) return;
        }
        if (asset) {
            betweenSources.run();
            try (InputStream input = resources.getAssets().open(name + ".xml")) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(input, "UTF-8");
                readTags(parser, reader);
            }
        }
    }

    private static void readTags(XmlPullParser parser, TagReader reader)
            throws IOException, XmlPullParserException {
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) reader.read(parser);
        }
    }
}
