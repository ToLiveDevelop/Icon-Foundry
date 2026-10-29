package dev.iconmanager;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Integration checks against the user's installed pack and a separately supplied reference theme. */
public final class DeviceTests extends Instrumentation {
    private Bundle arguments;
    private int checks;

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runChecks();
            result.putString("stream", "\nOK: " + checks + " checks passed\n");
            finish(-1, result);
        } catch (Exception | AssertionError error) {
            android.util.Log.e("IconManagerTests", "Failure", error);
            result.putString("stream", "\nFAILED after " + checks + " checks: " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }

    private void runChecks() throws Exception {
        Context context = getTargetContext();
        if ("true".equals(arguments.getString("cleanup"))) {
            Files.deleteIfExists(new File(context.getExternalFilesDir(null), "reference.hnt").toPath());
            Files.deleteIfExists(new File(context.getExternalFilesDir(null), "device-check.hnt").toPath());
            Files.deleteIfExists(new File(context.getFilesDir(), "device-check.hnt").toPath());
            check(true, "Device test artifacts removed");
            return;
        }
        verifyCoverAndReplacement(context);
        Bitmap previewSource = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        previewSource.eraseColor(android.graphics.Color.RED);
        try {
            for (IconShape shape : IconShape.values()) {
                Bitmap preview = shape.preview(previewSource);
                try {
                    check(preview.getPixel(96, 96) == android.graphics.Color.RED,
                            "Shape preview preserves example icon colors");
                    check(shape == IconShape.NONE ? preview.sameAs(previewSource)
                                    : android.graphics.Color.alpha(preview.getPixel(0, 0)) == 0,
                            "Shape preview applies selected native mask or preserves unmasked icon");
                } finally { preview.recycle(); }
            }
        } finally { previewSource.recycle(); }
        IconRepository repository = new IconRepository(context);
        String packName = arguments.getString("pack", "com.maxpatchs.premium");
        List<IconRepository.PackInfo> packs = repository.packs();
        check(packs.stream().anyMatch(p -> p.packageName().equals(packName)), "Pack visibility/discovery");
        for (IconRepository.PackInfo info : packs) {
            check(info.badge() != null && info.badge().getWidth() == 96
                    && info.badge().getHeight() == 96, "Discovered pack has an application icon for picker");
        }
        IconRepository.Pack pack = repository.load(packName);
        verifyMaskGeometry(context, repository, pack, packs);
        verifyWallpaper(context, pack);
        Bundle discovered = new Bundle();
        discovered.putString("stream", "Available packs: " + packs.stream().map(IconRepository.PackInfo::label).toList() + "\n");
        sendStatus(0, discovered);
        verifyCrossPackAndTemplates(context, repository, pack, packs);
        check(pack.badge != null && pack.badge.getWidth() == 96, "Selected pack includes its application icon");
        check(MainActivity.themesIntent(context.getPackageManager()).getComponent() != null,
                "Honor Themes button resolves to an installed activity");
        check(pack.icons.size() >= 3000, "Premium catalogue includes alternatives");
        verifySafReplacement(context, pack);
        check(pack.mappings.size() >= 4000, "Component mappings loaded from real appfilter");
        List<IconRepository.App> apps = repository.apps();
        Draft currentDraft = Draft.load(context);
        if (!currentDraft.pack.isEmpty() && !currentDraft.pack.equals(pack.packageName)) {
            IconRepository.Pack current = repository.load(currentDraft.pack);
            List<String> currentCover = ThemeEngine.coverIcons(current, apps, currentDraft.overrides);
            check(currentCover.size() == 12 && currentCover.stream().map(IconRepository::resourceName).distinct().count() == 12,
                    "Current user pack generates twelve distinct cover icons");
            Bitmap currentBitmap = ThemeEngine.cover(current, apps, currentDraft.overrides);
            currentBitmap.recycle();
            Bundle report = new Bundle();
            report.putString("stream", "Current cover (" + current.label + "): " + currentCover + "\n");
            sendStatus(0, report);
        }
        check(!apps.isEmpty(), "Launcher activities visible without QUERY_ALL_PACKAGES");
        long matching = apps.stream().filter(app -> pack.automatic(app) != null).count();
        check(matching > 0, "Installed apps matched to pack");
        IconRepository.App phone = apps.stream().filter(app -> app.component().getPackageName().equals("com.hihonor.contacts")
                && app.component().getClassName().equals("com.android.contacts.activities.DialtactsActivity"))
                .findFirst().orElseThrow();
        IconRepository.App contacts = apps.stream().filter(app -> app.component().getPackageName().equals("com.hihonor.contacts")
                && app.component().getClassName().equals("com.android.contacts.activities.PeopleActivity"))
                .findFirst().orElseThrow();
        String phoneIcon = pack.mappings.get("com.android.contacts/com.android.contacts.activities.DialtactsActivity");
        String contactsIcon = pack.mappings.get("com.android.contacts/com.android.contacts.activities.PeopleActivity");
        check(phoneIcon.equals("lg_phone") && contactsIcon.equals("google_contacts"), "Real pack canonical Contacts mappings");
        check(phoneIcon.equals(pack.automatic(phone)), "HONOR dialer resolves to canonical phone, not OEM gallery entry");
        check(contactsIcon.equals(pack.automatic(contacts)), "HONOR Contacts resolves to canonical contacts");
        List<String> coverSelection = ThemeEngine.coverIcons(pack, apps, Map.of());
        check(coverSelection.size() == 12, "Real Premium cover contains twelve icons");
        check(coverSelection.stream().map(IconRepository::resourceName).distinct().count() == 12,
                "Cover contains no repeated resource names");
        IconRepository.Pack sparse = new IconRepository.Pack(pack.packageName, pack.label, pack.resources,
                pack.badge, pack.manager);
        sparse.icons.putAll(pack.icons);
        List<String> alphabetical = pack.icons.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER
                .thenComparing(java.util.Comparator.naturalOrder())).limit(12).toList();
        check(ThemeEngine.coverIcons(sparse, apps, Map.of()).equals(alphabetical),
                "Pack without popular mappings fills twelve cells alphabetically");
        sparse.byPackage.put("com.android.camera2", "google_phone");
        IconRepository.App unmatchedCamera = new IconRepository.App(
                new ComponentName("com.hihonor.camera", "com.hihonor.camera.Camera"), "Camera");
        check(ThemeEngine.coverIcons(sparse, List.of(unmatchedCamera), Map.of()).get(0).equals("google_phone"),
                "Unmapped installed OEM activity still searches alternative category mappings");
        sparse.byPackage.clear();
        String firstResource = alphabetical.get(0);
        String secondResource = alphabetical.get(1);
        IconRepository.App manualContacts = new IconRepository.App(new ComponentName(
                "com.hihonor.contacts", "com.android.contacts.activities.PeopleActivity"), "Contacts");
        IconRepository.App manualPhone = new IconRepository.App(new ComponentName(
                "com.hihonor.contacts", "com.android.contacts.activities.DialtactsActivity"), "Phone");
        List<String> mixedCover = ThemeEngine.coverIcons(sparse, List.of(manualContacts, manualPhone),
                Map.of(manualContacts.key(), "dev.first.pack:" + firstResource,
                        manualPhone.key(), "dev.second.pack:" + firstResource));
        check(mixedCover.size() == 12 && mixedCover.get(0).equals("dev.first.pack:" + firstResource)
                && mixedCover.get(1).equals(secondResource),
                "Alphabetical fill skips duplicate resource names even across different packs");
        check(coverSelection.get(0).equals(contactsIcon) && coverSelection.get(1).equals(phoneIcon),
                "Auto cover starts with distinct Contacts and Phone icons");
        List<IconRepository.App> reversed = new ArrayList<>(apps);
        java.util.Collections.reverse(reversed);
        check(coverSelection.equals(ThemeEngine.coverIcons(pack, reversed, Map.of())),
                "Popular cover selection does not depend on alphabetical application order");
        check(!ThemeEngine.coverIcons(pack, apps, Map.of(contacts.key(), "")).contains(contactsIcon),
                "Auto cover respects system-icon exclusion without restoring base pack icon");
        String externalContact = context.getPackageName() + ":app_icon";
        check(ThemeEngine.coverIcons(pack, apps, Map.of(contacts.key(), externalContact)).get(0).equals(externalContact),
                "Popular cover respects cross-pack manual assignment");
        Bitmap popularCover = ThemeEngine.cover(pack, apps, Map.of());
        try {
            for (int i = 0; i < 12; i++) {
                Bitmap expectedIcon = pack.render(coverSelection.get(i), 140);
                try {
                    Bitmap pixelExpected = Bitmap.createBitmap(140, 140, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(pixelExpected);
                    canvas.drawColor(android.graphics.Color.BLACK);
                    canvas.drawBitmap(expectedIcon, 0, 0, null);
                    Bitmap actualIcon = Bitmap.createBitmap(popularCover, 60 + i % 3 * 230, 290 + i / 3 * 230, 140, 140);
                    try { check(actualIcon.sameAs(pixelExpected), "Cover cell matches selected popular application pixels"); }
                    finally { actualIcon.recycle(); pixelExpected.recycle(); }
                } finally { expectedIcon.recycle(); }
            }
        } finally { popularCover.recycle(); }
        Map<String, String> contactTargets = ThemeEngine.targets(List.of(phone, contacts), pack, Map.of());
        check(phoneIcon.equals(contactTargets.get(phone.component().getClassName())), "Dialer has its own activity PNG");
        check(contactsIcon.equals(contactTargets.get("com.hihonor.contacts")), "Contacts has correct package fallback independent of launcher ordering");
        check(contactsIcon.equals(contactTargets.get(contacts.component().getClassName())), "Contacts activity is distinct from dialer");
        check(pack.selected(phone, Map.of(phone.key(), contactsIcon)).equals(contactsIcon), "Manual override wins over compatibility mapping");
        for (boolean full : List.of(false, true)) {
            Map<String, String> targets = ThemeEngine.exportTargets(apps, pack, Map.of(), full);
            check(phoneIcon.equals(targets.get(phone.component().getClassName()))
                    && contactsIcon.equals(targets.get("com.hihonor.contacts")), "Contacts/Dialer separated in both scopes");
        }
        check(IconRepository.component("ComponentInfo{dev.example/.Main}").getClassName().equals("dev.example.Main"), "Relative component normalization");
        check(IconRepository.component(":BROWSER") == null, "Category aliases are not package names");
        check(IconRepository.component("ComponentInfo{../../escape/.Main}") == null, "Unsafe target rejected");
        check(ThemeEngine.filename("MyTheme").equals("MyTheme.hnt"), "Filename extension");
        check(ThemeEngine.filename("Моя тема.HNT").equals("Моя тема.hnt"), "Canonical .hnt extension with Unicode name");
        for (String invalid : List.of("", "../bad", "a\\b", "a:b", ".hnt", "a\nb", "bad?")) {
            expectFailure(() -> ThemeEngine.filename(invalid), "Invalid filename: " + invalid);
        }
        expectFailure(() -> ThemeEngine.validateTree(context, Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload")), "Wrong export directory");
        String path = arguments.getString("sample",
                new File(context.getExternalFilesDir(null), "reference.hnt").getPath());
        ThemeEngine.Format format = ThemeEngine.inspect(context, Uri.fromFile(new File(path)));
        check(format.layerSize() == 294 && format.iconSize() == 218 && format.contentSize() == 196, "Real Honor icon geometry");
        Bitmap sample = pack.render("google_phone", 196);
        check(sample.getWidth() == 196 && sample.getHeight() == 196, "Real installed drawable rendered");
        sample.recycle();
        String chosen = pack.icons.keySet().iterator().next();
        IconRepository.App unlisted = new IconRepository.App(new ComponentName("dev.test.unlisted", "dev.test.unlisted.Main"), "Unlisted");
        Map<String, String> overrides = new LinkedHashMap<>();
        check(pack.selected(unlisted, overrides) == null, "Missing app has no invented mapping");
        overrides.put(unlisted.key(), chosen);
        check(pack.selected(unlisted, overrides).equals(chosen), "Arbitrary icon assigned to missing app");
        overrides.put(unlisted.key(), "");
        check(pack.selected(unlisted, overrides) == null, "System icon override");
        IconRepository.App alias = new IconRepository.App(new ComponentName("dev.test.unlisted", "dev.test.unlisted.Alias"), "Alias");
        overrides.put(unlisted.key(), chosen);
        overrides.put(alias.key(), "");
        Map<String, String> targets = ThemeEngine.targets(List.of(unlisted, alias), pack, overrides);
        check(targets.size() == 1 && targets.containsKey(unlisted.component().getClassName()), "Excluded alias has no package fallback");
        overrides.put(alias.key(), chosen);
        check(ThemeEngine.targets(List.of(unlisted, alias), pack, overrides).containsKey("dev.test.unlisted"), "Consistent aliases have package fallback");
        IconRepository.App shared = new IconRepository.App(new ComponentName("dev.test.other", unlisted.component().getClassName()), "Shared");
        overrides.put(shared.key(), pack.icons.keySet().stream().filter(name -> !name.equals(chosen)).findFirst().orElseThrow());
        Map<String, String> sharedTargets = ThemeEngine.targets(List.of(unlisted, shared), pack, overrides);
        check(sharedTargets.size() == 2 && sharedTargets.containsKey("dev.test.other")
                && sharedTargets.containsKey("dev.test.unlisted"), "Shared activity classes use distinct package keys");
        overrides.put(alias.key(), "");
        expectFailure(() -> ThemeEngine.targets(List.of(unlisted, alias, shared), pack, overrides), "Ambiguous per-activity mapping is explicitly rejected");
        overrides.remove(alias.key());
        List<IconRepository.App> all = new ArrayList<>(apps);
        all.add(unlisted);
        Bitmap cover = ThemeEngine.cover(pack, all, overrides);
        check(cover.getWidth() == 720 && cover.getHeight() == 1280, "Auto cover dimensions");
        ThemeEngine.Built built;
        try {
            built = ThemeEngine.build(context, "Проверка & <иконки>", false, IconShape.NONE, false, pack, all, overrides, cover, (done, total) -> {});
        } finally { cover.recycle(); }
        try {
            check(built.apps() == matching + 1, "Exact exported app count including manual override");
            Map<String, String> expected = ThemeEngine.targets(all, pack, overrides);
            ThemeEngine.verifyArchive(built.file(), expected.size(), IconShape.NONE, false);
            try (ZipFile zip = new ZipFile(built.file())) {
                String xml = new String(zip.getInputStream(zip.getEntry("description.xml")).readAllBytes(), StandardCharsets.UTF_8);
                check(xml.contains("&amp;") && xml.contains("&lt;"), "Title escaped as XML");
                check(xml.contains("<HnTheme>"), "Honor metadata root retained");
                check(zip.size() == 6 && zip.getEntry("preview/icon_small.jpg") != null,
                        "Icon-only theme includes catalogue thumbnail and explicit preview directory");
                try (ZipInputStream icons = new ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                    int count = 0;
                    for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                        byte[] bytes = icons.readAllBytes();
                        if (!entry.getName().endsWith(".png")) continue;
                        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        check(bitmap != null && bitmap.getWidth() == 192 && bitmap.getHeight() == 192, "Reference-compatible flat icon dimensions");
                        check(!entry.getName().contains("/") && expected.containsKey(
                                entry.getName().substring(0, entry.getName().length() - 4)), "Flat package/activity resource path");
                        count++;
                        bitmap.recycle();
                    }
                    check(count == expected.size(), "Every target has one flat PNG");
                }
            }
            File artifact = new File(context.getFilesDir(), "device-check.hnt");
            Files.copy(built.file().toPath(), artifact.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Bundle status = new Bundle();
            status.putString("stream", "Real pack: " + pack.label + ", icons: " + pack.icons.size()
                    + ", apps: " + apps.size() + ", mapped: " + matching + ", output: " + artifact + "\n");
            sendStatus(0, status);
        } finally { Files.delete(built.file().toPath()); }
        java.util.Set<String> installedPackages = new java.util.HashSet<>();
        for (IconRepository.App app : apps) installedPackages.add(app.component().getPackageName());
        String futurePackage = pack.byPackage.keySet().stream().filter(name -> !installedPackages.contains(name))
                .findFirst().orElseThrow();
        Map<String, String> installedTargets = ThemeEngine.exportTargets(apps, pack, Map.of(), false);
        Map<String, String> fullTargets = ThemeEngine.exportTargets(apps, pack, Map.of(), true);
        check(!installedTargets.containsKey(futurePackage), "Installed-only excludes future application");
        check(fullTargets.get(futurePackage).equals(pack.byPackage.get(futurePackage)), "Full export contains future application's package");
        check(fullTargets.size() > installedTargets.size(), "Full scope includes uninstalled mapped apps");
        IconRepository.App mapped = apps.stream().filter(app -> pack.automatic(app) != null).findFirst().orElseThrow();
        Map<String, String> excluded = new LinkedHashMap<>();
        for (IconRepository.App app : apps) {
            if (app.component().getPackageName().equals(mapped.component().getPackageName())) excluded.put(app.key(), "");
        }
        check(!ThemeEngine.exportTargets(apps, pack, excluded, true).containsKey(mapped.component().getPackageName()),
                "Full export respects system-icon exclusion without restoring package fallback");
        check(ThemeEngine.exportTargets(all, pack, overrides, true).get(unlisted.component().getPackageName()).equals(chosen),
                "Full export retains manual assignment for unmapped application");
        Bitmap fullCover = ThemeEngine.cover(pack, apps, Map.of());
        ThemeEngine.Built complete;
        try {
            complete = ThemeEngine.build(context, "Full pack check", true, IconShape.ROUNDED_SQUARE, true, pack, apps, Map.of(), fullCover, (done, total) -> {});
        } finally { fullCover.recycle(); }
        try {
            ThemeEngine.verifyArchive(complete.file(), fullTargets.size(), IconShape.ROUNDED_SQUARE, true, pack.fallbackSize());
            verifyFolderModule(complete.file());
            try (ZipFile zip = new ZipFile(complete.file());
                 ZipInputStream icons = new ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                java.util.Set<String> names = new java.util.HashSet<>();
                for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                    byte[] bytes = icons.readAllBytes();
                    if (entry.getName().equals("icon_mask.png")) continue;
                    names.add(entry.getName());
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    check(bitmap != null && bitmap.getWidth() == 192 && bitmap.getHeight() == 192, "Full-pack PNG dimensions");
                    bitmap.recycle();
                }
                check(names.contains(futurePackage + ".png") && names.size() == fullTargets.size(),
                        "Full archive contains every intended target, including future install");
            }
            try (ZipFile zip = new ZipFile(complete.file());
                 ZipInputStream icons = new ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                boolean phoneFound = false, contactsFound = false;
                for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                    if (!entry.getName().equals(phone.component().getClassName() + ".png")
                            && !entry.getName().equals("com.hihonor.contacts.png")) continue;
                    byte[] bytes = icons.readAllBytes();
                    Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    boolean dialer = entry.getName().equals(phone.component().getClassName() + ".png");
                    Bitmap expected = pack.render(dialer ? phoneIcon : contactsIcon, 192);
                    try { check(actual.sameAs(expected), "Actual exported Contacts/Dialer pixels match intended resource"); }
                    finally { actual.recycle(); expected.recycle(); }
                    if (dialer) phoneFound = true; else contactsFound = true;
                }
                check(phoneFound && contactsFound, "Actual full archive contains phone activity and Contacts package");
            }
            ThemeEngine.Format flat = ThemeEngine.inspect(context, Uri.fromFile(complete.file()));
            check(flat.iconSize() == 192, "Flat HnTheme can be inspected as reference");
            Files.copy(complete.file().toPath(), new File(context.getFilesDir(), "device-check.hnt").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            Bundle report = new Bundle();
            report.putString("stream", "Full export: " + fullTargets.size() + " PNG files, "
                    + complete.apps() + " application mappings\n");
            sendStatus(0, report);
        } finally { Files.delete(complete.file().toPath()); }
        verifyShapes(context, pack, unlisted, chosen);
        File invalid = new File(context.getCacheDir(), "invalid-test.hnt");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(invalid))) {
                zip.putNextEntry(new ZipEntry("description.xml"));
                zip.write("<notHonor/>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("icons")); zip.write(new byte[]{1, 2}); zip.closeEntry();
            }
            expectFailure(() -> ThemeEngine.inspect(context, Uri.fromFile(invalid)), "Unsupported theme rejected");
        } finally { Files.deleteIfExists(invalid.toPath()); }
        check(context.getCacheDir().listFiles((dir, name) -> name.startsWith("sample-")).length == 0, "Reference temporary files cleaned up");
    }

    private void verifyMaskGeometry(Context context, IconRepository repository, IconRepository.Pack base,
                                    List<IconRepository.PackInfo> packs) throws Exception {
        Bitmap padded = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.RED);
        new android.graphics.Canvas(padded).drawRect(48, 48, 144, 144, paint);
        Bitmap adaptive = IconShape.previewSource(new android.graphics.drawable.AdaptiveIconDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.RED),
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        try {
            for (Bitmap source : List.of(padded, adaptive)) {
                Bitmap rounded = IconShape.ROUNDED_SQUARE.preview(source, 176);
                Bitmap squircle = IconShape.SQUIRCLE.preview(source, 176);
                try {
                    check(!rounded.sameAs(squircle), "Rounded square and squircle differ with adaptive or padded source");
                    check(IconRepository.Pack.visibleBounds(rounded).width() == 176
                            && IconRepository.Pack.visibleBounds(squircle).width() == 176,
                            "Preview fills chosen mask size despite source padding");
                } finally { rounded.recycle(); squircle.recycle(); }
            }
        } finally { padded.recycle(); adaptive.recycle(); }
        List<IconRepository.Pack> measured = new ArrayList<>();
        measured.add(base);
        for (String label : List.of("Fluid Premium Icon Pack", "Mello", "Vera")) {
            IconRepository.PackInfo info = packs.stream().filter(item -> item.label().equals(label)).findFirst().orElseThrow();
            measured.add(repository.load(info.packageName()));
        }
        for (IconRepository.Pack pack : measured) {
            List<String> names = pack.mappings.values().stream().distinct().sorted().toList();
            List<Integer> sizes = new ArrayList<>();
            for (int i = 0; i < Math.min(48, names.size()); i++) {
                Bitmap icon = pack.render(names.get(i * names.size() / Math.min(48, names.size())), 192);
                try {
                    var bounds = IconRepository.Pack.visibleBounds(icon);
                    if (!bounds.isEmpty()) sizes.add(Math.max(bounds.width(), bounds.height()));
                } finally { icon.recycle(); }
            }
            sizes.sort(Integer::compare);
            int expected = sizes.get(sizes.size() / 2);
            check(pack.fallbackSize() == expected, "Mask size equals sampled median visible size, not a fixed canvas inset");
            Bitmap mask = IconShape.SQUARE.mask(pack.fallbackSize());
            try {
                check(IconRepository.Pack.visibleBounds(mask).width() == expected,
                        "Export mask has same visible width as typical pack icon");
            } finally { mask.recycle(); }
            Bundle report = new Bundle();
            report.putString("stream", "Mask size (" + pack.label + "): " + expected + "/192 px\n");
            sendStatus(0, report);
        }
    }

    private void verifyWallpaper(Context context, IconRepository.Pack pack) throws Exception {
        File directory = new File(context.getCacheDir(), "wallpaper-check");
        Files.createDirectories(directory.toPath());
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
        };
        File image = new File(directory, "selected.jpg");
        Bitmap wallpaper = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888);
        wallpaper.eraseColor(android.graphics.Color.GREEN);
        try (FileOutputStream output = new FileOutputStream(image)) {
            check(wallpaper.compress(Bitmap.CompressFormat.JPEG, 95, output), "Test wallpaper encoded");
        } finally { wallpaper.recycle(); }
        IconRepository.App app = new IconRepository.App(new ComponentName("dev.test.wallpaper", "dev.test.wallpaper.Main"), "Test");
        String icon = pack.icons.keySet().iterator().next();
        Bitmap cover = ThemeEngine.cover(pack, List.of(app), Map.of(app.key(), icon));
        Uri exportedWallpaper = null;
        try {
            ThemeEngine.Built built = ThemeEngine.build(context, "Wallpaper check", false, IconShape.CIRCLE, true,
                    pack, List.of(app), Map.of(app.key(), icon), cover, image, (done, total) -> {});
            try {
                ThemeEngine.verifyArchive(built.file(), 1, IconShape.CIRCLE, true, pack.fallbackSize(), true);
                try (ZipFile zip = new ZipFile(built.file())) {
                    byte[] selected = Files.readAllBytes(image.toPath());
                    check(java.util.Arrays.equals(selected, zip.getInputStream(zip.getEntry("wallpaper/home_wallpaper_0.jpg")).readAllBytes()),
                            "Theme home wallpaper contains selected image, not reference or default artwork");
                    check(java.util.Arrays.equals(selected, zip.getInputStream(zip.getEntry("wallpaper/unlock_wallpaper_0.jpg")).readAllBytes()),
                            "Lock wallpaper contains same explicitly chosen image");
                }
            } finally { Files.delete(built.file().toPath()); }
            String filename = "_IconFoundry_wallpaper_" + java.util.UUID.randomUUID() + ".hnt";
            exportedWallpaper = ThemeEngine.export(context, "Wallpaper SAF check", filename, false,
                    IconShape.NONE, false, pack, List.of(app), Map.of(app.key(), icon), cover,
                    Uri.parse(Draft.load(context).tree), null, image, (done, total) -> {}).uri();
            try (var input = context.getContentResolver().openInputStream(exportedWallpaper);
                 ZipInputStream zip = new ZipInputStream(input)) {
                int walls = 0;
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    if (entry.getName().endsWith("wallpaper_0.jpg")) {
                        check(java.util.Arrays.equals(zip.readAllBytes(), Files.readAllBytes(image.toPath())),
                                "Actual Honor/Themes export contains selected wallpaper bytes");
                        walls++;
                    }
                }
                check(walls == 2, "Actual SAF export includes home and lock wallpaper");
            }
            Draft draft = new Draft();
            draft.pack = pack.packageName;
            draft.wallpaperPath = image.getName();
            TemplateStore store = new TemplateStore(isolated);
            TemplateStore.Entry saved = store.save(draft, pack.label, false);
            byte[] expected = Files.readAllBytes(image.toPath());
            Files.delete(image.toPath());
            Draft restored = store.open(saved.id());
            check(java.util.Arrays.equals(expected, Files.readAllBytes(new File(directory, restored.wallpaperPath).toPath())),
                    "Template owns wallpaper independently of original file and restores it");
            draft.wallpaperPath = "";
            draft.templateId = saved.id();
            store.save(draft, pack.label, false);
            check(store.open(saved.id()).wallpaperPath.isEmpty(), "Removing wallpaper persists when template is updated");
            File invalid = new File(directory, "invalid.jpg");
            Files.writeString(invalid.toPath(), "not an image");
            expectFailure(() -> ThemeEngine.build(context, "Invalid wallpaper", false, IconShape.NONE, false,
                    pack, List.of(app), Map.of(app.key(), icon), cover, invalid, (done, total) -> {}),
                    "Unreadable selected wallpaper stops export instead of silently omitting it");
        } finally {
            cover.recycle();
            if (exportedWallpaper != null) check(android.provider.DocumentsContract.deleteDocument(
                    context.getContentResolver(), exportedWallpaper), "Temporary wallpaper theme removed without applying");
            for (File file : directory.listFiles()) Files.delete(file.toPath());
            Files.delete(directory.toPath());
        }
    }

    private void verifyShapes(Context context, IconRepository.Pack pack, IconRepository.App app, String icon) throws Exception {
        java.util.Set<Integer> silhouettes = new java.util.HashSet<>();
        Bitmap cover = ThemeEngine.cover(pack, List.of(app), Map.of(app.key(), icon));
        try {
            for (IconShape shape : IconShape.values()) {
                boolean clearFolderBackground = shape.ordinal() % 2 == 0;
                ThemeEngine.Built built = ThemeEngine.build(context, "Shape check", false, shape, clearFolderBackground, pack,
                        List.of(app), Map.of(app.key(), icon), cover, (done, total) -> {});
                try {
                    ThemeEngine.verifyArchive(built.file(), 1, shape, clearFolderBackground, pack.fallbackSize());
                    try (ZipFile zip = new ZipFile(built.file())) {
                        check((zip.getEntry(ThemeEngine.LAUNCHER) != null) == clearFolderBackground,
                                "Folder background override appears only when enabled");
                    }
                    try (ZipFile zip = new ZipFile(built.file());
                         ZipInputStream icons = new ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                        boolean hasMask = false;
                        for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                            byte[] bytes = icons.readAllBytes();
                            Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            try {
                                if (entry.getName().equals("icon_mask.png")) {
                                    hasMask = true;
                                    check(actual.getWidth() == 192 && actual.getHeight() == 192, "Native flat mask dimensions");
                                    int[] pixels = new int[192 * 192];
                                    actual.getPixels(pixels, 0, 192, 0, 0, 192, 192);
                                    boolean black = true, padding = true;
                                    int area = 0;
                                    for (int y = 0; y < 192; y++) for (int x = 0; x < 192; x++) {
                                        int pixel = pixels[y * 192 + x];
                                        if ((pixel & 0x00ffffff) != 0) black = false;
                                        if (android.graphics.Color.alpha(pixel) != 0) {
                                            area++;
                                            int inset = (192 - pack.fallbackSize()) / 2;
                                            if (android.graphics.Color.alpha(pixel) >= 128
                                                    && (x < inset || y < inset || x >= 192 - inset || y >= 192 - inset)) padding = false;
                                        }
                                    }
                                    check(black && padding && area > 20000, "Mask is black silhouette with correct padding: " + shape
                                            + " black=" + black + " padding=" + padding + " area=" + area);
                                    check(android.graphics.Color.alpha(actual.getPixel(96, 96)) == 255, "Mask centre is fully opaque");
                                    check(silhouettes.add(java.util.Arrays.hashCode(pixels)), "Every selectable shape has different silhouette");
                                    boolean roundCorner = shape != IconShape.SQUARE;
                                    int inset = (192 - pack.fallbackSize()) / 2;
                                    check((android.graphics.Color.alpha(actual.getPixel(inset + 1, inset + 1)) == 0) == roundCorner, "Mask corner geometry");
                                } else {
                                    Bitmap expected = pack.render(icon, 192);
                                    try { check(actual.sameAs(expected), "Pack icon pixels unchanged by fallback shape"); }
                                    finally { expected.recycle(); }
                                }
                            } finally { actual.recycle(); }
                        }
                        check(hasMask == (shape != IconShape.NONE), "No-mask option omits native mask entirely");
                    }
                } finally { Files.delete(built.file().toPath()); }
            }
        } finally { cover.recycle(); }
        Context testContext = getContext();
        Draft saved = new Draft();
        saved.shape = IconShape.TEARDROP;
        saved.fullPack = true;
        saved.clearFolderBackground = false;
        saved.overrides.put(app.key(), icon);
        saved.save(testContext);
        Draft restored = Draft.load(testContext);
        check(restored.shape == IconShape.TEARDROP && restored.fullPack && !restored.clearFolderBackground
                && icon.equals(restored.overrides.get(app.key())), "Shape persists with scope and manual assignments");
        org.json.JSONObject legacy = new org.json.JSONObject(testContext.getSharedPreferences("draft", 0).getString("json", ""));
        legacy.put("templateName", "Old.hnt").put("layerSize", 294).put("iconSize", 218).put("contentSize", 196);
        testContext.getSharedPreferences("draft", 0).edit().putString("json", legacy.toString()).commit();
        Draft migrated = Draft.load(testContext);
        check(migrated.shape == IconShape.TEARDROP && migrated.fullPack
                && icon.equals(migrated.overrides.get(app.key())), "Removing template settings preserves user choices");
        migrated.save(testContext);
        check(!new org.json.JSONObject(testContext.getSharedPreferences("draft", 0).getString("json", ""))
                .has("templateName"), "Obsolete template selection no longer saved");
        testContext.getSharedPreferences("draft", 0).edit().putString("json", "{}").commit();
        check(Draft.load(testContext).shape == IconShape.NONE, "Old drafts preserve unmasked behaviour");
        check(Draft.load(testContext).clearFolderBackground, "Old drafts enable agreed folder background fix by default");
        android.app.Activity activity = startActivitySync(new android.content.Intent(context, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            waitForWork(activity);
            runOnMainSync(() -> {
                check(findText(activity.getWindow().getDecorView(), context.getString(R.string.templates)) != null,
                        "Application starts with template list");
                check(field(activity, "pack") == null, "Start screen does not load an icon pack");
                ((MainActivity) activity).openEditor();
            });
            waitForWork(activity);
            runOnMainSync(() -> {
                android.util.TypedValue accent = new android.util.TypedValue();
                activity.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
                check(accent.data == android.graphics.Color.rgb(255, 193, 7), "App theme uses amber accent");
                android.widget.LinearLayout root = (android.widget.LinearLayout) field(activity, "root");
                check(((android.graphics.drawable.ColorDrawable) root.getBackground()).getColor()
                        == android.graphics.Color.BLACK, "Main screen background is black");
                android.widget.CheckBox folder = (android.widget.CheckBox) findText(
                        root, activity.getString(R.string.folder_background));
                check(folder != null && folder.isChecked() == ((Draft) field(activity, "draft")).clearFolderBackground,
                        "Folder background toggle reflects persisted setting");
                android.widget.Spinner spinner = findShapePicker(activity.getWindow().getDecorView());
                android.widget.ImageView shapeImage = (android.widget.ImageView) field(activity, "shapePreview");
                float previewDensity = context.getResources().getDisplayMetrics().density;
                check(shapeImage.getScaleType() == android.widget.ImageView.ScaleType.FIT_CENTER
                        && shapeImage.getPaddingLeft() >= 8 * previewDensity - 1
                        && shapeImage.getPaddingTop() >= 8 * previewDensity - 1
                        && shapeImage.getPaddingRight() >= 8 * previewDensity - 1
                        && shapeImage.getPaddingBottom() >= 8 * previewDensity - 1,
                        "Shape preview preserves aspect ratio and safe margin on every edge without changing exported mask");
                check(spinner != null, "Shape picker is wired into the real Activity");
                check(spinner.getAdapter().getCount() == IconShape.values().length, "UI exposes all six shape options");
                for (IconShape shape : IconShape.values()) {
                    check(context.getString(shape.label).equals(spinner.getAdapter().getItem(shape.ordinal())),
                            "Shape picker label matches exported enum value");
                    android.view.View entry = spinner.getAdapter().getDropDownView(shape.ordinal(), null, spinner);
                    check(entry instanceof android.widget.TextView && !(entry instanceof android.widget.CheckedTextView),
                            "Shape list uses plain rows without OEM checkmark overlays");
                    android.widget.TextView label = (android.widget.TextView) entry;
                    check(label.getCurrentTextColor() == android.graphics.Color.WHITE
                            && label.getMinHeight() >= 56 * context.getResources().getDisplayMetrics().density - 1,
                            "Dropdown rows have readable labels and sufficient height");
                }
                check(findText(activity.getWindow().getDecorView(), "Выбрать образец .hnt") == null,
                        "Sample selection removed from real screen");
                android.widget.Button choose = (android.widget.Button) findText(
                        activity.getWindow().getDecorView(), context.getString(R.string.choose_pack));
                choose.performClick();
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "packDialog");
                check(dialog != null && dialog.isShowing(), "Pack picker opens synchronously before discovery completes");
                check(findText(dialog.getWindow().getDecorView(), "Понятно") == null,
                        "Pack picker has no redundant dismissal button");
                check(findText(dialog.getWindow().getDecorView(), context.getString(R.string.loading)) != null,
                        "Picker immediately shows loading state");
                android.widget.ProgressBar progress = (android.widget.ProgressBar) field(activity, "operationProgress");
                check(progress.getVisibility() == android.view.View.VISIBLE && progress.isIndeterminate(),
                        "Background operation shows an indeterminate indicator");
            });
            waitForWork(activity);
            runOnMainSync(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "packDialog");
                android.widget.ListView list = findList(dialog.getWindow().getDecorView());
                check(list != null && list.getAdapter().getCount() > 0, "Skeleton replaced with discovered pack list");
                android.widget.LinearLayout item = (android.widget.LinearLayout) list.getAdapter().getView(0, null, list);
                check(((android.widget.ImageView) item.getChildAt(0)).getDrawable() != null,
                        "Loaded pack row displays an icon");
                String label = ((android.widget.TextView) item.getChildAt(1)).getText().toString();
                IconRepository.PackInfo info = (IconRepository.PackInfo) list.getAdapter().getItem(0);
                check(label.equals(info.label()) && item.getChildCount() == 2,
                        "Loaded pack row displays application title without a package-name subtitle");
                dialog.dismiss();
                check(((android.widget.ProgressBar) field(activity, "operationProgress")).getVisibility()
                        == android.view.View.GONE, "Operation indicator hides after completion");
            });
            verifyOperationUi((MainActivity) activity);
            verifyThemeEditing((MainActivity) activity, pack);
            verifyWallpaperUi((MainActivity) activity);
            verifyIconSourcePicker((MainActivity) activity, pack);
            verifyCropUi((MainActivity) activity);
            verifyTemplateExportUi((MainActivity) activity);
        } finally { runOnMainSync(activity::finish); }
    }

    private void verifyTemplateExportUi(MainActivity activity) throws Exception {
        Draft original = (Draft) field(activity, "draft");
        TemplateStore originalStore = (TemplateStore) field(activity, "templates");
        Draft settings = Draft.fromJson(original.toJson());
        settings.templateId = "";
        settings.title = "Template integration";
        settings.coverPath = "";
        settings.wallpaperPath = "";
        settings.fullPack = false;
        settings.overrides.clear();
        File directory = new File(activity.getCacheDir(), "template-export-check");
        Files.createDirectories(directory.toPath());
        Context isolated = new android.content.ContextWrapper(activity) {
            @Override public File getFilesDir() { return directory; }
        };
        TemplateStore store = new TemplateStore(isolated);
        List<String> filenames = new ArrayList<>();
        Uri tree = Uri.parse(settings.tree);
        try {
            runOnMainSync(() -> {
                setField(activity, "draft", settings);
                setField(activity, "templates", store);
                activity.openEditor();
            });
            waitForWork(activity);
            String firstId = "";
            for (int step = 0; step < 3; step++) {
                String filename = "_IconManager_template_" + java.util.UUID.randomUUID() + ".hnt";
                filenames.add(filename);
                int current = step;
                runOnMainSync(() -> {
                    ((android.widget.EditText) field(activity, "filename")).setText(filename);
                    ((android.widget.EditText) field(activity, "title")).setText("Template revision " + current);
                    ((android.widget.CheckBox) field(activity, "saveAsNew")).setChecked(current == 2);
                    findText(activity.getWindow().getDecorView(), activity.getString(R.string.export)).performClick();
                });
                waitForWork(activity);
                check(ThemeEngine.findDocument(activity.getContentResolver(), tree, filename) != null,
                        "Template integration writes verified .hnt through real export button");
                List<TemplateStore.Entry> entries = store.list();
                check(entries.size() == (step == 2 ? 2 : 1), "Automatic template save has expected create/update/copy semantics");
                if (step == 0) firstId = entries.get(0).id();
                else check(step == 2 ? !settings.templateId.equals(firstId) : settings.templateId.equals(firstId),
                        "Successful export tracks original template identity unless save-as-new selected");
                runOnMainSync(() -> {
                    android.app.AlertDialog dialog = (android.app.AlertDialog) field(activity, "exportDialog");
                    check(dialog != null && dialog.isShowing(), "Success appears only after both theme and template save");
                    check(((android.widget.TextView) dialog.findViewById(android.R.id.message)).getText()
                            .toString().contains(activity.getString(R.string.template_saved, entries.get(0).name())),
                            "Success identifies the saved template and timestamp");
                    dialog.dismiss();
                });
            }
            String originalId = firstId;
            runOnMainSync(() -> findText(activity.getWindow().getDecorView(),
                    activity.getString(R.string.templates)).performClick());
            waitForWork(activity);
            runOnMainSync(() -> {
                android.widget.ListView list = findList(activity.getWindow().getDecorView());
                check(list.getAdapter().getCount() == 2, "Start list shows both saved templates");
                android.widget.LinearLayout item = (android.widget.LinearLayout) list.getAdapter().getView(0, null, list);
                check(item.getChildAt(0) instanceof android.widget.ImageView
                        && ((android.widget.ImageView) item.getChildAt(0)).getDrawable() != null,
                        "Template row shows its base pack application icon");
                TemplateStore.Entry entry = (TemplateStore.Entry) list.getAdapter().getItem(0);
                check(entry.packLabel().contentEquals(item.getChildAt(0).getContentDescription())
                        && ((android.widget.TextView) item.getChildAt(1)).getText().toString().contains(entry.name()),
                        "Template row associates icon with base pack while retaining name and timestamp");
                check(list.getAdapter().getView(1, item, list) == item, "Template icon rows support list recycling");
                list.performItemClick(list.getAdapter().getView(1, null, list), 1, 1);
            });
            waitForWork(activity);
            runOnMainSync(() -> {
                check(((Draft) field(activity, "draft")).templateId.equals(originalId),
                        "Selecting a template opens that template for subsequent overwrite");
                check(((android.widget.EditText) field(activity, "title")).getText().toString().equals("Template revision 1"),
                        "Opening older template restores its independent settings");
            });
            byte[] before = Files.readAllBytes(new File(directory, "templates.json").toPath());
            String failedName = "_IconManager_template_" + java.util.UUID.randomUUID() + ".hnt";
            filenames.add(failedName);
            runOnMainSync(() -> {
                Draft current = (Draft) field(activity, "draft");
                @SuppressWarnings("unchecked")
                List<IconRepository.App> apps = (List<IconRepository.App>) field(activity, "apps");
                current.overrides.put(apps.get(0).key(), "dev.missing.iconpack:missing");
                ((android.widget.EditText) field(activity, "filename")).setText(failedName);
                findText(activity.getWindow().getDecorView(), activity.getString(R.string.export)).performClick();
            });
            waitForWork(activity);
            check(java.util.Arrays.equals(before, Files.readAllBytes(new File(directory, "templates.json").toPath())),
                    "Failed icon resolution leaves previously saved templates byte-for-byte unchanged");
            check(ThemeEngine.findDocument(activity.getContentResolver(), tree, failedName) == null,
                    "Failed icon resolution does not create a theme document");
            runOnMainSync(() -> {
                android.app.AlertDialog error = (android.app.AlertDialog) field(activity, "errorDialog");
                check(field(activity, "progressDialog") == null, "Progress popup dismissed before export error");
                check(error != null && error.isShowing(), "Unavailable custom pack produces explicit error, not success");
                check(error.getButton(android.app.AlertDialog.BUTTON_POSITIVE) == null
                                || error.getButton(android.app.AlertDialog.BUTTON_POSITIVE).getVisibility() == android.view.View.GONE,
                        "Error dialog has no redundant acknowledgement action");
                error.onBackPressed();
                check(!error.isShowing(), "Back dismisses error dialog without acknowledgement button");
            });
            runOnMainSync(() -> findText(activity.getWindow().getDecorView(),
                    activity.getString(R.string.templates)).performClick());
            waitForWork(activity);
            runOnMainSync(() -> {
                android.widget.ListView list = findList(activity.getWindow().getDecorView());
                android.widget.LinearLayout recycled = (android.widget.LinearLayout) list.getAdapter().getView(0, null, list);
                recycled = (android.widget.LinearLayout) list.getAdapter().getView(1, recycled, list);
                android.widget.Button delete = (android.widget.Button) recycled.getChildAt(2);
                check(!delete.isFocusable(), "Template delete action does not block row selection");
                delete.performClick();
                check(!(boolean) field(activity, "editing"), "Delete button does not open template editor");
                android.app.AlertDialog confirmation = (android.app.AlertDialog) field(activity, "deleteDialog");
                check(((android.widget.TextView) confirmation.findViewById(android.R.id.message)).getText()
                        .toString().contains(((TemplateStore.Entry) list.getAdapter().getItem(1)).name()),
                        "Template delete requests confirmation");
                confirmation.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            waitForIdleSync();
            check(java.util.Arrays.equals(before, Files.readAllBytes(new File(directory, "templates.json").toPath())),
                    "Cancelling deletion preserves template store unchanged");
            runOnMainSync(() -> {
                android.widget.ListView list = findList(activity.getWindow().getDecorView());
                android.widget.LinearLayout item = (android.widget.LinearLayout) list.getAdapter().getView(1, null, list);
                ((android.widget.Button) item.getChildAt(2)).performClick();
                ((android.app.AlertDialog) field(activity, "deleteDialog"))
                        .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            });
            waitForIdleSync();
            waitForWork(activity);
            check(store.list().size() == 1 && !store.list().get(0).id().equals(originalId),
                    "Confirmed deletion removes only selected template");
            runOnMainSync(() -> {
                Draft current = (Draft) field(activity, "draft");
                check(current.templateId.isEmpty() && !current.title.isEmpty() && !current.overrides.isEmpty(),
                        "Deleting active template detaches draft without losing edited settings");
                check(findList(activity.getWindow().getDecorView()).getAdapter().getCount() == 1,
                        "Template list refreshes after deletion");
            });
            for (int i = 0; i < 3; i++) check(ThemeEngine.findDocument(activity.getContentResolver(), tree, filenames.get(i)) != null,
                    "Deleting template leaves exported theme intact");
            expectFailure(() -> store.delete(originalId), "Deleting nonexistent template fails explicitly");
            runOnMainSync(() -> {
                android.widget.ListView list = findList(activity.getWindow().getDecorView());
                android.widget.LinearLayout item = (android.widget.LinearLayout) list.getAdapter().getView(0, null, list);
                ((android.widget.Button) item.getChildAt(2)).performClick();
                ((android.app.AlertDialog) field(activity, "deleteDialog"))
                        .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            });
            waitForIdleSync();
            waitForWork(activity);
            check(store.list().isEmpty(), "Deleting last template persists empty store");
            runOnMainSync(() -> check(findText(activity.getWindow().getDecorView(),
                    activity.getString(R.string.no_templates)) != null, "Empty state shown after last deletion"));
        } finally {
            runOnMainSync(() -> {
                setField(activity, "draft", original);
                setField(activity, "templates", originalStore);
                try { original.save(activity); } catch (Exception error) { throw new AssertionError(error); }
                android.app.AlertDialog dialog = (android.app.AlertDialog) field(activity, "exportDialog");
                if (dialog != null) dialog.dismiss();
                dialog = (android.app.AlertDialog) field(activity, "errorDialog");
                if (dialog != null) dialog.dismiss();
            });
            for (String filename : filenames) {
                Uri document = ThemeEngine.findDocument(activity.getContentResolver(), tree, filename);
                if (document != null) check(android.provider.DocumentsContract.deleteDocument(activity.getContentResolver(), document),
                        "Temporary integration theme removed without touching user themes");
            }
            Files.deleteIfExists(new File(directory, "templates.json").toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void verifyCrossPackAndTemplates(Context context, IconRepository repository, IconRepository.Pack pack,
                                              List<IconRepository.PackInfo> packs) throws Exception {
        IconRepository.PackInfo other = packs.stream().filter(info -> !info.packageName().equals(pack.packageName))
                .findFirst().orElse(null);
        String source = other == null ? context.getPackageName() : other.packageName();
        String resource = other == null ? "app_icon" : repository.load(source).icons.keySet().iterator().next();
        String reference = IconRepository.reference(source, resource);
        IconRepository.App app = new IconRepository.App(new ComponentName("dev.test.custom", "dev.test.custom.Main"), "Custom");
        Map<String, String> overrides = Map.of(app.key(), reference);
        Bitmap expected = pack.render(reference, 192);
        Bitmap cover = ThemeEngine.cover(pack, List.of(app), overrides);
        try {
            ThemeEngine.Built built = ThemeEngine.build(context, "Mixed packs", false, IconShape.CIRCLE, true,
                    pack, List.of(app), overrides, cover, (done, total) -> {});
            try (ZipFile zip = new ZipFile(built.file());
                 ZipInputStream icons = new ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                boolean found = false;
                for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                    if (!entry.getName().equals("dev.test.custom.png")) continue;
                    byte[] bytes = icons.readAllBytes();
                    Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    try { check(expected.sameAs(actual), "Cross-pack export preserves exact external icon pixels"); }
                    finally { actual.recycle(); }
                    found = true;
                }
                check(found, "Cross-pack icon exported under target application's name");
            } finally { Files.delete(built.file().toPath()); }
            check(ThemeEngine.exportTargets(List.of(), pack, overrides, true).get("dev.test.custom").equals(reference),
                    "Full-pack export retains manual assignments for temporarily uninstalled apps");
            check(ThemeEngine.exportTargets(List.of(), pack, overrides, false).isEmpty(),
                    "Installed-only export excludes uninstalled apps without deleting saved assignments");
            expectFailure(() -> pack.render("dev.missing.iconpack:missing", 192), "Removed external pack fails explicitly");
            expectFailure(() -> pack.render(context.getPackageName() + ":iconmanager_absent_resource", 192),
                    "Removed external resource fails explicitly");
            File isolatedDirectory = new File(context.getCacheDir(), "template-store-check");
            Files.createDirectories(isolatedDirectory.toPath());
            Context isolated = new android.content.ContextWrapper(context) {
                @Override public File getFilesDir() { return isolatedDirectory; }
            };
            TemplateStore store = new TemplateStore(isolated);
            File storeFile = new File(isolated.getFilesDir(), "templates.json");
            File coverFile = new File(isolated.getFilesDir(), "template-test.jpg");
            File restoredCover = new File(isolated.getFilesDir(), "template-cover.jpg");
            try {
                Files.deleteIfExists(storeFile.toPath());
                try (FileOutputStream output = new FileOutputStream(coverFile)) {
                    cover.compress(Bitmap.CompressFormat.JPEG, 90, output);
                }
                byte[] coverBytes = Files.readAllBytes(coverFile.toPath());
                Draft settings = new Draft();
                settings.pack = pack.packageName;
                settings.title = "My mixed theme";
                settings.filename = "Mixed.hnt";
                settings.tree = "content://test/tree";
                settings.shape = IconShape.SQUIRCLE;
                settings.fullPack = true;
                settings.clearFolderBackground = false;
                settings.coverPath = coverFile.getName();
                settings.overrides.putAll(overrides);
                settings.overrides.put("dev.test.excluded/dev.test.excluded.Main", "");
                TemplateStore.Entry first = store.save(settings, pack.label, false);
                check(first.name().startsWith(pack.label + " · ") && first.name().matches(".*\\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*"),
                        "Template name contains pack title and precise timestamp");
                check(store.list().size() == 1, "First successful save creates one template");
                check(!first.settings().has("icons") && first.settings().getJSONObject("overrides").getString(app.key()).equals(reference),
                        "Template stores references rather than icon images");
                settings.title = "Unsaved change";
                Files.write(coverFile.toPath(), new byte[]{1, 2, 3});
                Draft restored = store.open(first.id());
                check(restored.title.equals("My mixed theme") && restored.filename.equals("Mixed.hnt")
                        && restored.pack.equals(pack.packageName) && restored.tree.equals("content://test/tree")
                        && restored.shape == IconShape.SQUIRCLE && restored.fullPack && !restored.clearFolderBackground,
                        "All main template settings round-trip independently from the editing draft");
                check(restored.overrides.equals(settings.overrides), "Template restores external references and system exclusions");
                check(java.util.Arrays.equals(coverBytes, Files.readAllBytes(restoredCover.toPath())),
                        "Template preserves its own cover independently from subsequent changes");
                restored.title = "Updated";
                TemplateStore.Entry updated = store.save(restored, pack.label, false);
                check(updated.id().equals(first.id()) && store.list().size() == 1
                        && store.open(first.id()).title.equals("Updated") && !updated.savedAt().isBefore(first.savedAt()),
                        "Working template is overwritten and timestamp refreshed");
                TemplateStore.Entry copied = store.save(restored, pack.label, true);
                check(!copied.id().equals(first.id()) && store.list().size() == 2,
                        "Save-as-new creates a separate template without overwriting original");
                check(store.list().get(0).id().equals(copied.id()), "Newest templates appear first");
                org.json.JSONObject legacy = settings.toJson();
                legacy.getJSONObject("overrides").put(app.key(), "google_phone");
                check(Draft.fromJson(legacy).overrides.get(app.key()).equals(pack.packageName + ":google_phone"),
                        "Old manual assignments migrate to explicit original-pack references");
            } finally {
                Files.deleteIfExists(storeFile.toPath());
                Files.deleteIfExists(coverFile.toPath());
                Files.deleteIfExists(restoredCover.toPath());
                Files.deleteIfExists(isolatedDirectory.toPath());
            }
        } finally { expected.recycle(); cover.recycle(); }
    }

    private void verifyIconSourcePicker(MainActivity activity, IconRepository.Pack pack) throws Exception {
        Draft draft = (Draft) field(activity, "draft");
        Map<String, String> original = new LinkedHashMap<>(draft.overrides);
        IconRepository.App app = new IconRepository.App(new ComponentName("dev.test.picker", "dev.test.picker.Main"), "Picker");
        java.util.concurrent.atomic.AtomicBoolean selected = new java.util.concurrent.atomic.AtomicBoolean();
        String[] source = {pack.packageName};
        try {
            runOnMainSync(() -> {
                activity.showIcons(app, () -> selected.set(true));
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "iconDialog");
                android.widget.Button button = (android.widget.Button) findText(dialog.getWindow().getDecorView(),
                        activity.getString(R.string.icon_source, pack.label));
                check(button != null, "Icon picker initially offers current base pack");
                android.widget.Button automatic = (android.widget.Button) findText(dialog.getWindow().getDecorView(),
                        activity.getString(R.string.automatic));
                android.widget.Button originalButton = (android.widget.Button) findText(dialog.getWindow().getDecorView(),
                        activity.getString(R.string.keep_original));
                float density = activity.getResources().getDisplayMetrics().density;
                var left = (android.widget.LinearLayout.LayoutParams) automatic.getLayoutParams();
                var right = (android.widget.LinearLayout.LayoutParams) originalButton.getLayoutParams();
                var actionRow = (android.widget.LinearLayout.LayoutParams)
                        ((android.view.View) automatic.getParent()).getLayoutParams();
                check(left.rightMargin + right.leftMargin >= 12 * density - 1
                        && actionRow.topMargin >= 8 * density - 1 && actionRow.bottomMargin >= 12 * density - 1,
                        "Icon picker top actions have horizontal and vertical spacing");
                check(!(boolean) field(activity, "busy"), "Opening icon picker does not start loading other packs");
                button.performClick();
            });
            waitForWork(activity);
            runOnMainSync(() -> {
                android.app.Dialog picker = (android.app.Dialog) field(activity, "packDialog");
                android.widget.ListView list = findList(picker.getWindow().getDecorView());
                int index = 0;
                for (int i = 0; i < list.getAdapter().getCount(); i++) {
                    IconRepository.PackInfo info = (IconRepository.PackInfo) list.getAdapter().getItem(i);
                    if (!info.packageName().equals(pack.packageName)) { index = i; break; }
                }
                source[0] = ((IconRepository.PackInfo) list.getAdapter().getItem(index)).packageName();
                list.performItemClick(list.getAdapter().getView(index, null, list), index, index);
                check((boolean) field(activity, "busy"), "Selected pack starts loading only after explicit choice");
            });
            waitForWork(activity);
            runOnMainSync(() -> {
                check(((IconRepository.Pack) field(activity, "pack")).packageName.equals(pack.packageName),
                        "Choosing custom icon source does not change base pack");
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "iconDialog");
                android.widget.GridView grid = findGrid(dialog.getWindow().getDecorView());
                check(grid != null && grid.getAdapter().getCount() > 0, "Chosen source catalogue appears in icon grid");
                grid.performItemClick(grid.getAdapter().getView(0, null, grid), 0, 0);
                check(selected.get() && draft.overrides.get(app.key()).startsWith(source[0] + ":"),
                        "Picker persists both resource and source package");
            });
        } finally {
            runOnMainSync(() -> {
                draft.overrides.clear(); draft.overrides.putAll(original);
                try { draft.save(activity); } catch (Exception error) { throw new AssertionError(error); }
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "iconDialog");
                if (dialog != null) dialog.dismiss();
                dialog = (android.app.Dialog) field(activity, "packDialog");
                if (dialog != null) dialog.dismiss();
            });
        }
    }

    private android.widget.GridView findGrid(android.view.View view) {
        if (view instanceof android.widget.GridView grid) return grid;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.GridView found = findGrid(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void verifyFolderModule(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file);
             ZipInputStream module = new ZipInputStream(zip.getInputStream(zip.getEntry("com.hihonor.android.launcher")))) {
            java.util.Set<String> paths = new java.util.HashSet<>();
            for (ZipEntry entry; (entry = module.getNextEntry()) != null;) {
                paths.add(entry.getName());
                org.xmlpull.v1.XmlPullParser xml = android.util.Xml.newPullParser();
                xml.setInput(new java.io.ByteArrayInputStream(module.readAllBytes()), "UTF-8");
                int colors = 0;
                for (int event = xml.next(); event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; event = xml.next()) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        if ("color".equals(xml.getName())) {
                            check("magic_gray_4".equals(xml.getAttributeValue(null, "name")),
                                    "Only proven launcher preview tint resource is overridden");
                            check("#00000000".equals(xml.nextText()), "Preview background override has zero alpha");
                            colors++;
                        } else check("resources".equals(xml.getName()), "Launcher override contains no unrelated elements");
                    }
                }
                check(colors == 1, "Exactly one launcher-local color per mode");
            }
            check(paths.equals(java.util.Set.of("theme.xml", "night/theme.xml")),
                    "Folder fix applies only to launcher in day and night modes");
        }
    }

    private void verifyCoverAndReplacement(Context context) throws Exception {
        Bitmap wide = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(wide);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.RED); canvas.drawRect(0, 0, 800, 900, paint);
        paint.setColor(android.graphics.Color.BLUE); canvas.drawRect(800, 0, 1600, 900, paint);
        try {
            for (float x : new float[]{0, .5f, 1}) {
                Bitmap crop = ThemeEngine.cropCover(wide, x, .5f, 1);
                try {
                    check(crop.getWidth() == 720 && crop.getHeight() == 1280, "Cropped cover uses exact theme aspect ratio");
                    check(crop.getPixel(0, 0) == android.graphics.Color.RED
                            || crop.getPixel(0, 0) == android.graphics.Color.BLUE, "Crop fills frame without letterbox padding");
                    if (x != .5f) check(crop.getPixel(360, 640) == (x == 0 ? android.graphics.Color.RED
                            : android.graphics.Color.BLUE), "Horizontal positioning selects intended image area");
                } finally { crop.recycle(); }
            }
            Bitmap tall = Bitmap.createBitmap(900, 2000, Bitmap.Config.ARGB_8888);
            tall.eraseColor(android.graphics.Color.GREEN);
            try {
                Bitmap crop = ThemeEngine.cropCover(tall, .5f, 1, 3);
                try { check(crop.getPixel(0, 0) == android.graphics.Color.GREEN
                        && crop.getPixel(719, 1279) == android.graphics.Color.GREEN, "Portrait crop and zoom leave no empty edges"); }
                finally { crop.recycle(); }
            } finally { tall.recycle(); }
        } finally { wide.recycle(); }
        File original = File.createTempFile("replace-test-", ".hnt", context.getCacheDir());
        File replacement = File.createTempFile("replacement-test-", ".hnt", context.getCacheDir());
        try {
            Files.writeString(original.toPath(), "Original theme content longer than replacement");
            Files.writeString(replacement.toPath(), "New theme");
            ThemeEngine.replaceVerified(context, Uri.fromFile(original), replacement);
            check(Files.readString(original.toPath()).equals("New theme"), "Replacement truncates original and verifies written bytes");
            Files.writeString(original.toPath(), "Keep original");
            Files.delete(replacement.toPath());
            expectFailure(() -> ThemeEngine.replaceVerified(context, Uri.fromFile(original), replacement),
                    "Missing replacement reports error");
            check(Files.readString(original.toPath()).equals("Keep original"), "Failed replacement preserves original bytes");
        } finally {
            Files.deleteIfExists(original.toPath()); Files.deleteIfExists(replacement.toPath());
        }
    }

    private void verifyWallpaperUi(MainActivity activity) throws Exception {
        Draft draft = (Draft) field(activity, "draft");
        String oldPath = draft.wallpaperPath;
        File selected = new File(activity.getFilesDir(), "wallpaper.jpg");
        byte[] previous = selected.exists() ? Files.readAllBytes(selected.toPath()) : null;
        File source = File.createTempFile("wallpaper-input-", ".jpg", activity.getCacheDir());
        Bitmap image = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888);
        image.eraseColor(android.graphics.Color.BLUE);
        try (FileOutputStream output = new FileOutputStream(source)) {
            image.compress(Bitmap.CompressFormat.JPEG, 95, output);
        } finally { image.recycle(); }
        try {
            runOnMainSync(() -> activity.onActivityResult(13, android.app.Activity.RESULT_OK,
                    new android.content.Intent().setData(Uri.fromFile(source))));
            waitForWork(activity);
            runOnMainSync(() -> {
                check(draft.wallpaperPath.equals("wallpaper.jpg") && selected.isFile(),
                        "Wallpaper image selection stores private copy and updates draft");
                check(((android.widget.ImageView) field(activity, "wallpaperPreview")).getDrawable() != null,
                        "Selected wallpaper is shown in editor");
                findText(activity.getWindow().getDecorView(), activity.getString(R.string.remove_wallpaper)).performClick();
                check(draft.wallpaperPath.isEmpty(), "Not including wallpaper explicitly clears draft setting");
            });
            Files.writeString(source.toPath(), "invalid image");
            runOnMainSync(() -> activity.onActivityResult(13, android.app.Activity.RESULT_OK,
                    new android.content.Intent().setData(Uri.fromFile(source))));
            waitForWork(activity);
            runOnMainSync(() -> {
                android.app.AlertDialog error = (android.app.AlertDialog) field(activity, "errorDialog");
                check(field(activity, "progressDialog") == null && error != null && error.isShowing(),
                        "Invalid wallpaper closes loading popup and shows explicit error");
                check(draft.wallpaperPath.isEmpty(), "Invalid selection does not replace previous wallpaper setting");
                error.dismiss();
            });
        } finally {
            if (previous == null) Files.deleteIfExists(selected.toPath());
            else Files.write(selected.toPath(), previous);
            Files.deleteIfExists(source.toPath());
            runOnMainSync(() -> {
                draft.wallpaperPath = oldPath;
                try { draft.save(activity); } catch (Exception error) { throw new AssertionError(error); }
            });
        }
    }

    private void verifyCropUi(MainActivity activity) throws Exception {
        Bitmap source = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(source);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.RED); canvas.drawRect(0, 0, 800, 900, paint);
        paint.setColor(android.graphics.Color.BLUE); canvas.drawRect(800, 0, 1600, 900, paint);
        runOnMainSync(() -> {
            CoverCropView view = new CoverCropView(activity, source);
            view.layout(0, 0, 720, 1280);
            view.setZoom(2);
            android.view.MotionEvent down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 600, 640, 0);
            android.view.MotionEvent move = android.view.MotionEvent.obtain(0, 50, android.view.MotionEvent.ACTION_MOVE, 0, 640, 0);
            try { view.onTouchEvent(down); view.onTouchEvent(move); }
            finally { down.recycle(); move.recycle(); }
            Bitmap crop = view.crop();
            try {
                check(crop.getPixel(360, 640) == android.graphics.Color.BLUE, "Crop view pan and zoom affect actual output");
            } finally { crop.recycle(); }
            try {
                java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("cropCover", Bitmap.class);
                method.setAccessible(true); method.invoke(activity, source);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            android.app.Dialog dialog = (android.app.Dialog) field(activity, "cropDialog");
            check(dialog != null && dialog.isShowing(), "Cover selection opens crop editor");
            check(findText(dialog.getWindow().getDecorView(), activity.getString(R.string.crop_use)) != null,
                    "Crop editor requires explicit use action");
            check(findText(dialog.getWindow().getDecorView(), "Понятно") == null,
                    "Crop editor has no redundant dismissal button");
            dialog.onBackPressed();
            check(!dialog.isShowing(), "Back cancels crop without an extra button");
        });
        waitForIdleSync();
        check(source.isRecycled(), "Cancelling crop releases source bitmap without saving a cover");
    }

    private void verifySafReplacement(Context context, IconRepository.Pack pack) throws Exception {
        Draft draft = Draft.load(context);
        check(!draft.tree.isEmpty(), "Device has user-granted export directory for replacement integration check");
        Uri tree = Uri.parse(draft.tree);
        String name = "_IconManager_check_" + java.util.UUID.randomUUID() + ".hnt";
        IconRepository.App app = new IconRepository.App(new ComponentName("dev.iconmanager.check", "dev.iconmanager.check.Main"), "Check");
        String icon = pack.icons.keySet().iterator().next();
        Map<String, String> overrides = Map.of(app.key(), icon);
        Bitmap cover = ThemeEngine.cover(pack, List.of(app), overrides);
        Uri created = null;
        try {
            ThemeEngine.Result first = ThemeEngine.export(context, "Original", name, false, IconShape.NONE, false,
                    pack, List.of(app), overrides, cover, tree, null, (done, total) -> {});
            created = first.uri();
            check(ThemeEngine.findDocument(context.getContentResolver(), tree, name.toUpperCase(java.util.Locale.ROOT))
                    .equals(created), "Existing filename found case-insensitively through actual SAF provider");
            byte[] original;
            try (var input = context.getContentResolver().openInputStream(created)) { original = input.readAllBytes(); }
            expectFailure(() -> ThemeEngine.export(context, "Unconfirmed", name, false, IconShape.NONE, true,
                    pack, List.of(app), overrides, cover, tree, null, (done, total) -> {}),
                    "Existing SAF theme cannot be overwritten without confirmation");
            try (var input = context.getContentResolver().openInputStream(created)) {
                check(java.util.Arrays.equals(original, input.readAllBytes()), "Unconfirmed SAF export leaves original bytes unchanged");
            }
            ThemeEngine.Result second = ThemeEngine.export(context, "Replacement", name, false, IconShape.CIRCLE, true,
                    pack, List.of(app), overrides, cover, tree, created, (done, total) -> {});
            check(second.uri().equals(created), "Confirmed replacement keeps document identity and filename");
            File saved = File.createTempFile("saf-replaced-", ".hnt", context.getCacheDir());
            try {
                try (var input = context.getContentResolver().openInputStream(created);
                     var output = new FileOutputStream(saved)) { input.transferTo(output); }
                ThemeEngine.verifyArchive(saved, 1, IconShape.CIRCLE, true, pack.fallbackSize());
                try (ZipFile zip = new ZipFile(saved)) {
                    String xml = new String(zip.getInputStream(zip.getEntry("description.xml")).readAllBytes(), StandardCharsets.UTF_8);
                    check(xml.contains("<title>Replacement</title>"), "Replaced SAF archive contains new theme metadata");
                }
            } finally { Files.delete(saved.toPath()); }
        } finally {
            cover.recycle();
            if (created != null) check(android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), created),
                    "Unique temporary SAF test theme removed; user themes untouched");
        }
    }

    private void verifyThemeEditing(MainActivity activity, IconRepository.Pack pack) throws Exception {
        runOnMainSync(() -> {
            android.widget.EditText title = (android.widget.EditText) field(activity, "title");
            android.widget.EditText filename = (android.widget.EditText) field(activity, "filename");
            String savedTitle = title.getText().toString(), savedFilename = filename.getText().toString();
            Draft editingDraft = (Draft) field(activity, "draft");
            String savedTree = editingDraft.tree;
            android.widget.Button export = (android.widget.Button) field(activity, "exportButton");
            title.setText("Button check");
            filename.setText("");
            check(!export.isEnabled(), "Export disabled without filename");
            filename.setText("ButtonCheck.hnt");
            check(export.isEnabled(), "Export enabled with loaded pack, title, filename and granted tree");
            IconRepository.Pack loadedPack = (IconRepository.Pack) field(activity, "pack");
            setField(activity, "pack", null);
            filename.setText("ButtonCheck.hnt");
            check(!export.isEnabled(), "Export disabled without loaded icon pack");
            setField(activity, "pack", loadedPack);
            @SuppressWarnings("unchecked")
            List<IconRepository.App> installedApps = (List<IconRepository.App>) field(activity, "apps");
            android.widget.ImageView shapeExample = (android.widget.ImageView) field(activity, "shapePreview");
            check(installedApps.stream().filter(app -> loadedPack.automatic(app) == null)
                            .anyMatch(app -> activity.getString(R.string.shape_example,
                                    activity.getString(editingDraft.shape.label), app.label())
                                    .contentEquals(shapeExample.getContentDescription()))
                            && shapeExample.getColorFilter() == null,
                    "Shape UI uses an unthemed installed application without recoloring");
            title.setText(" ");
            check(!export.isEnabled(), "Export disabled for whitespace title");
            title.setText("Button check");
            editingDraft.tree = "";
            filename.setText("ButtonCheck.hnt");
            check(!export.isEnabled(), "Export disabled without destination");
            editingDraft.tree = savedTree;
            filename.setText("bad/name");
            check(!export.isEnabled(), "Export disabled for invalid filename");
            title.setText(savedTitle); filename.setText(savedFilename);
            android.widget.Button coverButton = (android.widget.Button) findText(
                    activity.getWindow().getDecorView(), activity.getString(R.string.choose_cover));
            float density = activity.getResources().getDisplayMetrics().density;
            android.widget.LinearLayout.LayoutParams coverControls =
                    (android.widget.LinearLayout.LayoutParams) ((android.view.View) coverButton.getParent()).getLayoutParams();
            check(coverControls.leftMargin >= 16 * density - 1, "Cover controls have a separate gap from image preview");
            check(coverButton.getPaddingTop() <= 6 * density + 1
                    && coverButton.getMinHeight() >= 48 * density - 1,
                    "Compact button padding retains a 48dp touch target");
            try {
                title.setText("");
                activity.selectPack(pack);
                check(title.getText().toString().equals(pack.label), "Selecting pack fills empty theme title");
                title.setText("Custom name");
                activity.selectPack(pack);
                check(title.getText().toString().equals("Custom name"), "Selecting pack preserves nonempty theme title");
                android.widget.TextView titleLabel = findText(activity.getWindow().getDecorView(),
                        activity.getString(R.string.theme_title));
                android.widget.TextView filenameLabel = findText(activity.getWindow().getDecorView(),
                        activity.getString(R.string.theme_filename));
                check(titleLabel != null && titleLabel != title && titleLabel.getLabelFor() == title.getId(),
                        "Theme title retains a permanent associated label when filled");
                check(filenameLabel != null && filenameLabel != filename && filenameLabel.getLabelFor() == filename.getId(),
                        "Filename retains a permanent associated label when filled");
                check(filename.getText().toString().equals(savedFilename), "Pack selection preserves filename");
                android.widget.LinearLayout body = (android.widget.LinearLayout) title.getParent().getParent();
                check(findText(body.getChildAt(2), activity.getString(R.string.choose_pack)) != null,
                        "Pack selection is the first form card");
                java.util.concurrent.atomic.AtomicBoolean replaced = new java.util.concurrent.atomic.AtomicBoolean();
                android.app.AlertDialog cancel = activity.confirmReplacement("existing.hnt", () -> replaced.set(true));
                try { cancel.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick(); }
                finally { cancel.dismiss(); }
                check(!replaced.get(), "Cancelling replacement does not start write");
            } finally { title.setText(savedTitle); filename.setText(savedFilename); }
        });
        java.util.concurrent.atomic.AtomicBoolean replaced = new java.util.concurrent.atomic.AtomicBoolean();
        runOnMainSync(() -> {
            android.app.AlertDialog dialog = activity.confirmReplacement("existing.hnt", () -> replaced.set(true));
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        });
        waitForIdleSync();
        check(replaced.get(), "Replacement starts only after explicit positive confirmation");
    }

    private void verifyOperationUi(MainActivity activity) throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean succeeded = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.Callable<Void> saving = () -> {
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Test timed out");
            return null;
        };
        java.util.function.Consumer<Void> success = unused -> succeeded.set(true);
        java.lang.reflect.Method work = MainActivity.class.getDeclaredMethod(
                "work", java.util.concurrent.Callable.class, java.util.function.Consumer.class);
        work.setAccessible(true);
        try {
            runOnMainSync(() -> {
                try { work.invoke(activity, saving, success); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            Thread.sleep(300);
            runOnMainSync(() -> {
                android.widget.ProgressBar progress = (android.widget.ProgressBar) field(activity, "operationProgress");
                check(progress.getVisibility() == android.view.View.VISIBLE && progress.isIndeterminate(),
                        "Indeterminate indicator stays visible while background save is unfinished");
                check(!succeeded.get(), "No premature success while save is unfinished");
                android.app.Dialog popup = (android.app.Dialog) field(activity, "progressDialog");
                check(popup != null && popup.isShowing(), "Background work shows central progress popup");
                var window = popup.getWindow().getAttributes();
                check((window.flags & android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0
                        && window.dimAmount >= .6f && window.gravity == android.view.Gravity.CENTER,
                        "Progress popup has centered placement and dim backdrop");
                popup.onBackPressed();
                check(popup.isShowing(), "Progress popup cannot be dismissed with Back during work");
            });
        } finally { release.countDown(); }
        waitForWork(activity);
        runOnMainSync(() -> {
            check(succeeded.get(), "Success delivered after background save completes");
            check(field(activity, "progressDialog") == null, "Progress popup dismissed after success");
            check(((android.widget.ProgressBar) field(activity, "operationProgress")).getVisibility()
                    == android.view.View.GONE, "Save indicator removed on completion");
        });
        android.content.Intent intent = MainActivity.themesIntent(activity.getPackageManager());
        ActivityMonitor monitor = addMonitor(intent.getComponent().getClassName(), null, true);
        try {
            runOnMainSync(() -> {
                android.app.AlertDialog dialog = activity.showExportResult(
                        new ThemeEngine.Result(Uri.EMPTY, 1, "ui-check.hnt", false));
                try {
                    android.widget.TextView message = dialog.findViewById(android.R.id.message);
                    check(message.getText().toString().equals(activity.getString(
                            R.string.export_done, "Honor/Themes/ui-check.hnt")), "Success message only shows path and short instruction");
                    android.widget.Button open = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
                    check(open.getText().toString().equals(activity.getString(R.string.open_themes)), "Success includes Themes action");
                    check(dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE) == null
                                    || dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).getVisibility() == android.view.View.GONE,
                            "Success keeps Themes action but removes redundant acknowledgement");
                    open.performClick();
                } finally { dialog.dismiss(); }
            });
            waitForIdleSync();
            check(monitor.getHits() == 1, "Themes button launches correct application without applying a theme");
        } finally { removeMonitor(monitor); }
        runOnMainSync(() -> {
            android.app.AlertDialog dialog = activity.showExportResult(
                    new ThemeEngine.Result(Uri.EMPTY, 1, "folder-check.hnt", true));
            try {
                android.widget.TextView message = dialog.findViewById(android.R.id.message);
                check(message.getText().toString().equals(activity.getString(
                        R.string.export_done_full_theme, "Honor/Themes/folder-check.hnt")),
                        "Export with launcher module instructs applying whole theme");
            } finally { dialog.dismiss(); }
        });
    }

    private Object field(Object target, String name) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void waitForWork(android.app.Activity activity) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 60000;
        java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(true);
        do {
            runOnMainSync(() -> busy.set((boolean) field(activity, "busy")));
            if (!busy.get()) return;
            Thread.sleep(100);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("UI work did not finish in 60 seconds");
    }

    private android.widget.TextView findText(android.view.View view, String text) {
        if (view instanceof android.widget.TextView label && text.contentEquals(label.getText())) return label;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private android.widget.ListView findList(android.view.View view) {
        if (view instanceof android.widget.ListView list) return list;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.ListView found = findList(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private android.widget.Spinner findShapePicker(android.view.View view) {
        if (view instanceof android.widget.Spinner spinner) return spinner;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Spinner found = findShapePicker(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private void expectFailure(Throwing action, String label) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (Exception expected) { failed = true; }
        check(failed, label);
    }
    interface Throwing { void run() throws Exception; }
}
