package dev.iconmanager;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.StringReader;
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

    @Override public void runOnMainSync(Runnable runner) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        super.runOnMainSync(() -> {
            try { runner.run(); }
            catch (RuntimeException | AssertionError error) { failure.set(error); }
        });
        if (failure.get() instanceof RuntimeException error) throw error;
        if (failure.get() instanceof AssertionError error) throw error;
    }

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = -1;
        android.content.SharedPreferences draftPreferences = getTargetContext().getSharedPreferences("draft", 0);
        Map<String, ?> originalPreferences = draftPreferences.getAll();
        try {
            runChecks();
            result.putString("stream", "\nOK: " + checks + " checks passed\n");
        } catch (Exception | AssertionError error) {
            android.util.Log.e("IconManagerTests", "Failure", error);
            result.putString("stream", "\nFAILED after " + checks + " checks: " + android.util.Log.getStackTraceString(error));
            resultCode = 0;
        } finally {
            var edit = draftPreferences.edit().clear();
            for (var entry : originalPreferences.entrySet()) edit.putString(entry.getKey(), (String) entry.getValue());
            if (!edit.commit()) {
                resultCode = 0;
                result.putString("stream", "\nFAILED: could not restore draft preferences\n");
            }
        }
        finish(resultCode, result);
    }

    private void verifyPackDiscoveryStopsAtFirstMatch() throws Exception {
        StringBuilder xml = new StringBuilder("<appfilter>");
        for (int i = 0; i < 5000; i++) xml.append("<item/>");
        xml.append("</appfilter>");
        org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
        parser.setInput(new StringReader(xml.toString()));
        int[] visited = {0};
        IconRepository.readTags(parser, ignored -> visited[0]++, () -> visited[0] == 2);
        check(visited[0] == 2, "Pack discovery stops parsing appfilter after its first matching entry");
    }

    private void verifyVoLTE(Context context) throws Exception {
        IconRepository repository = new IconRepository(context);
        String packageName = arguments.getString("pack");
        if (packageName == null || packageName.isBlank()) {
            packageName = repository.packs().stream().findFirst().orElseThrow().packageName();
        }
        IconRepository.Pack pack = repository.load(packageName);
        String iconName = pack.icons.keySet().iterator().next();
        IconRepository.App app = new IconRepository.App(
                new ComponentName("dev.test.volte", "dev.test.volte.Main"), "VoLTE test");
        List<IconRepository.App> apps = List.of(app);
        Map<String, String> overrides = Map.of(app.key(), iconName);

        Draft disabled = new Draft();
        check(!Draft.fromJson(disabled.toJson()).hideVolte
                        && !Draft.fromJson(new org.json.JSONObject("{}")).hideVolte
                        && !disabled.toJson().has("hideVolte"),
                "VoLTE hiding defaults off and preserves legacy template serialization");
        Draft enabled = new Draft();
        enabled.hideVolte = true;
        check(Draft.fromJson(enabled.toJson()).hideVolte, "VoLTE choice round-trips through template settings");
        enabled.save(context);
        check(Draft.load(context).hideVolte, "VoLTE choice persists in the editable draft");

        Bitmap cover = ThemeEngine.cover(pack, apps, overrides);
        File standardFile = null;
        File hiddenFile = null;
        try {
            ThemeEngine.Built standard = ThemeEngine.build(context, "VoLTE off", false, IconShape.NONE, false,
                    pack, apps, overrides, cover, false, (done, total) -> {});
            standardFile = standard.file();
            ThemeEngine.Built hidden = ThemeEngine.build(context, "VoLTE on", false, IconShape.NONE, false,
                    pack, apps, overrides, cover, true, (done, total) -> {});
            hiddenFile = hidden.file();
            verifyVoLTEArchive(standardFile, false);
            verifyVoLTEArchive(hiddenFile, true);
        } finally {
            cover.recycle();
            if (standardFile != null) Files.deleteIfExists(standardFile.toPath());
            if (hiddenFile != null) Files.deleteIfExists(hiddenFile.toPath());
        }
    }

    private void verifyVoLTEArchive(File file, boolean expected) throws Exception {
        try (ZipFile archive = new ZipFile(file)) {
            ZipEntry module = archive.getEntry("com.android.systemui.status");
            check((module != null) == expected, expected
                    ? "Enabled VoLTE hiding adds the HONOR status-bar module"
                    : "Disabled VoLTE hiding adds no status-bar module");
            check(archive.getEntry("com.android.systemui") == null,
                    "VoLTE export does not add the unrelated SystemUI theme module");
            if (!expected) return;
            java.util.Set<String> remaining = new java.util.HashSet<>(
                    List.of("res/", "res/drawable-xxhdpi/",
                            "res/drawable-xxhdpi/ic_statusbar_volte.png",
                            "res/drawable-xxhdpi/ic_statusbar_volte_dl.png",
                            "res/drawable-xxhdpi/ic_statusbar_volte_dl_center.png",
                            "res/drawable-xxhdpi/ic_statusbar_volte_sig_dl.png",
                            "res/drawable-xxhdpi/ic_statusbar_incall_double_volte.png"));
            try (ZipInputStream status = new ZipInputStream(archive.getInputStream(module))) {
                for (ZipEntry entry; (entry = status.getNextEntry()) != null;) {
                    check(remaining.remove(entry.getName()), "Status-bar module contains only the VoLTE resource path");
                    if (!entry.getName().endsWith(".png")) continue;
                    byte[] bytes = status.readAllBytes();
                    Bitmap icon = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (icon == null) throw new AssertionError("VoLTE placeholder is not a PNG image");
                    try {
                        check(icon.getWidth() == 1 && icon.getHeight() == 60,
                                "VoLTE drawable placeholders are 1px wide");
                        boolean transparent = true;
                        for (int y = 0; y < icon.getHeight(); y++) {
                            for (int x = 0; x < icon.getWidth(); x++) {
                                transparent &= Color.alpha(icon.getPixel(x, y)) == 0;
                            }
                        }
                        check(transparent, "VoLTE placeholder is fully transparent");
                    } finally {
                        icon.recycle();
                    }
                }
            }
            check(remaining.isEmpty(), "Status-bar module replaces every standard, dual-SIM, and in-call VoLTE drawable");
        }
    }

    private void runChecks() throws Exception {
        Context context = getTargetContext();
        if ("true".equals(arguments.getString("volteOnly"))) {
            verifyVoLTE(context);
            return;
        }
        if ("true".equals(arguments.getString("themePreviewUiOnly"))) {
            verifyThemePreviewUiOnly(context);
            return;
        }
        if ("true".equals(arguments.getString("pngOnly"))) {
            verifyPngEncoding(context);
            String pngPack = arguments.getString("pngPack");
            if (pngPack != null && !pngPack.isBlank()) verifyPackPngEncoding(context, pngPack);
            return;
        }
        if ("true".equals(arguments.getString("packDiscoveryOnly"))) {
            verifyPackDiscoveryStopsAtFirstMatch();
            return;
        }
        if ("true".equals(arguments.getString("templateBackupOnly"))) {
            verifyTemplateBackup(context);
            return;
        }
        if ("true".equals(arguments.getString("multiPackOnly"))) {
            verifyMultiPack(new IconRepository(context).load(arguments.getString("pack", "com.maxpatchs.premium")));
            return;
        }
        if ("true".equals(arguments.getString("inlineStyleOnly"))) {
            verifyInlineStyleFixture(context);
            return;
        }
        if ("true".equals(arguments.getString("perPackResolutionUiOnly"))) {
            IconRepository repository = new IconRepository(context);
            IconRepository.Pack first = repository.load("com.maxpatchs.premium");
            String second = repository.packs().stream()
                    .filter(info -> !info.packageName().equals(first.packageName))
                    .findFirst().orElseThrow().packageName();
            verifyPerPackResolutionUi(context, first, repository.load(second));
            return;
        }
        if ("true".equals(arguments.getString("perPackResolutionOnly"))) {
            verifyPerPackResolution(context);
            return;
        }
        if ("true".equals(arguments.getString("cleanup"))) {
            Files.deleteIfExists(new File(context.getExternalFilesDir(null), "reference.hnt").toPath());
            Files.deleteIfExists(new File(context.getExternalFilesDir(null), "device-check.hnt").toPath());
            Files.deleteIfExists(new File(context.getFilesDir(), "device-check.hnt").toPath());
            check(true, "Device test artifacts removed");
            return;
        }
        if ("true".equals(arguments.getString("extraLauncherOnly"))) {
            verifyExtraLauncherEntries(context, new IconRepository(context));
            return;
        }
        verifyPositionFilter(context);
        if ("true".equals(arguments.getString("positionOnly"))) {
            verifyPositionUiFixture(context);
            return;
        }
        verifyCoverAndReplacement(context);
        verifyTemplateBackup(context);
        Bitmap previewSource = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        previewSource.eraseColor(android.graphics.Color.RED);
        try {
            for (IconShape shape : IconShape.values()) {
                if (shape == IconShape.FILE) continue;
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
        List<android.content.pm.ResolveInfo> launchers = context.getPackageManager().queryIntentActivities(
                new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME), 0);
        check(launchers.stream().noneMatch(launcher -> packs.stream().anyMatch(info ->
                        info.packageName().equals(launcher.activityInfo.packageName))),
                "Home launchers with internal appfilter resources are excluded from icon pack selection");
        check(packs.stream().noneMatch(info -> info.packageName().equals("com.or.launcher.oreo")),
                "Installed P Launcher is not offered as an icon pack");
        for (IconRepository.PackInfo info : packs) {
            check(info.badge() != null && info.badge().getWidth() == 96
                    && info.badge().getHeight() == 96, "Discovered pack has an application icon for picker");
        }
        IconRepository.Pack pack = repository.load(packName);
        verifyExtraLauncherEntries(context, repository);
        verifyNativeResolution(context, repository, packs, pack);
        if ("true".equals(arguments.getString("nativeResolutionOnly"))) return;
        verifyShapeExamples(context, pack);
        verifyFileMask(context, pack);
        verifySettingsResources(context);
        verifyOverlay(context, pack);
        verifyBackground(context, pack);
        verifyIconFilters(context, pack);
        if ("true".equals(arguments.getString("settingsOverlayOnly"))) {
            verifySettingsUi(context);
            return;
        }
        if (packs.stream().anyMatch(info -> info.packageName().equals("com.lknninex.mation"))) {
            IconRepository.Pack mation = repository.load("com.lknninex.mation");
            check(mation.icons.size() > 1000 && mation.mappings.size() > 1000 && !mation.icons.containsKey("an_icon"),
                    "Pack with template stubs in assets still loads the full res/xml catalogue");
        }
        verifyFileIcons(context, pack);
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
        if (!currentDraft.packs.isEmpty() && !currentDraft.packs.equals(List.of(pack.packageName))) {
            List<IconRepository.Pack> currentPacks = new ArrayList<>();
            for (String name : currentDraft.packs) currentPacks.add(repository.load(name));
            IconRepository.Pack current = IconRepository.combine(currentPacks);
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
        check(!contactTargets.containsKey(contacts.component().getClassName()),
                "Contacts activity uses its package icon instead of a global activity-class file");
        IconRepository.App googleContacts = new IconRepository.App(
                new ComponentName("com.google.android.contacts", contacts.component().getClassName()), "Google Contacts");
        Map<String, String> googleOverride = Map.of(googleContacts.key(), phoneIcon);
        Map<String, String> sharedContacts = ThemeEngine.targets(List.of(phone, contacts, googleContacts), pack, googleOverride);
        check(phoneIcon.equals(sharedContacts.get("com.google.android.contacts"))
                        && contactsIcon.equals(sharedContacts.get("com.hihonor.contacts"))
                        && phoneIcon.equals(sharedContacts.get(phone.component().getClassName()))
                        && !sharedContacts.containsKey(contacts.component().getClassName()),
                "Google Contacts sharing PeopleActivity with HONOR Contacts exports without a conflict");
        check(pack.selected(phone, Map.of(phone.key(), contactsIcon)).equals(contactsIcon), "Manual override wins over compatibility mapping");
        for (boolean full : List.of(false, true)) {
            Map<String, String> targets = ThemeEngine.exportTargets(apps, pack, Map.of(), full);
            check(phoneIcon.equals(targets.get(phone.component().getClassName()))
                    && contactsIcon.equals(targets.get("com.hihonor.contacts")), "Contacts/Dialer separated in both scopes");
        }
        check(IconRepository.component("ComponentInfo{dev.example/.Main}").getClassName().equals("dev.example.Main"), "Relative component normalization");
        verifyMultiPack(pack);
        check(IconRepository.component(":BROWSER") == null, "Category aliases are not package names");
        check(IconRepository.component("ComponentInfo{../../escape/.Main}") == null, "Unsafe target rejected");
        check(ThemeEngine.filename("MyTheme").equals("MyTheme.hnt"), "Filename extension");
        check(ThemeEngine.filename("Моя тема.HNT").equals("Моя тема.hnt"), "Canonical .hnt extension with Unicode name");
        for (String invalid : List.of("", "../bad", "a\\b", "a:b", ".hnt", "a\nb", "bad?")) {
            expectFailure(() -> ThemeEngine.filename(invalid), "Invalid filename: " + invalid);
        }
        var createdAt = java.time.LocalDateTime.of(2026, 10, 29, 15, 55, 19, 385_000_000);
        String dated = ThemeEngine.datedFilename("Моя тема", createdAt);
        check(dated.equals("Моя тема_2026-10-29_15-55-19.hnt"),
                "Automatic filename includes theme title and local date/time without milliseconds");
        check(!dated.equals(ThemeEngine.datedFilename("Моя тема", createdAt.plusSeconds(1))),
                "Timestamp distinguishes consecutive exports without changing theme title");
        check(ThemeEngine.datedFilename("Моя тема", createdAt, 2).equals("Моя тема_2026-10-29_15-55-19_2.hnt"),
                "Same-second filename collision receives numeric suffix");
        check(ThemeEngine.versionedTitle("Premium Icons", 2).equals("Premium Icons v2")
                        && ThemeEngine.versionedTitle("Т".repeat(100), 2).length() == 100
                        && !Character.isHighSurrogate(ThemeEngine.versionedTitle("😀".repeat(50), 2).charAt(97)),
                "Display title version suffix fits limit without splitting Unicode characters");
        check(ThemeEngine.nextThemeTitle(java.util.Set.of("mello", "mello v2", "mello v4", "mellow v9"), "Mello")
                        .equals("Mello v5")
                        && ThemeEngine.nextThemeTitle(java.util.Set.of("mello"), "Mello").equals("Mello v2")
                        && ThemeEngine.nextThemeTitle(java.util.Set.of("other v3"), "Mello").equals("Mello"),
                "Theme title version continues after the highest existing version");
        for (String name : List.of(" .a/b:c*?\"<>|\n", "Т".repeat(100), "\uD83D\uDE00".repeat(50))) {
            String generated = ThemeEngine.datedFilename(name, createdAt);
            check(generated.length() <= 100 && generated.getBytes(StandardCharsets.UTF_8).length <= 255
                            && ThemeEngine.filename(generated).equals(generated)
                            && generated.endsWith("_2026-10-29_15-55-19.hnt"),
                    "Automatic filename sanitizes forbidden characters and preserves timestamp within filesystem limits");
            check(generated.equals(new String(generated.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)),
                    "Truncating filename never splits a supplementary Unicode character");
        }
        expectFailure(() -> ThemeEngine.datedFilename(" \t ", createdAt), "Blank title cannot generate filename");
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
        Map<String, String> ambiguous = ThemeEngine.targets(List.of(unlisted, alias, shared), pack, overrides);
        check(!ambiguous.containsKey(unlisted.component().getClassName()) && !ambiguous.containsKey("dev.test.unlisted")
                        && ambiguous.containsKey("dev.test.other"),
                "Conflicting shared activity class is skipped instead of overriding another app");
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
                verifyDescription(xml.getBytes(StandardCharsets.UTF_8), "Проверка & <иконки>", ThemeEngine.credits(List.of(pack.label)));
                check(zip.size() == 6 && zip.getEntry("preview/icon_small.jpg") != null,
                        "Icon-only theme includes catalogue thumbnail and explicit preview directory");
                try (ZipInputStream icons = new ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                    int count = 0;
                    boolean dimensions = true, paths = true;
                    for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                        byte[] bytes = icons.readAllBytes();
                        if (!entry.getName().endsWith(".png")) continue;
                        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        dimensions &= bitmap != null && bitmap.getWidth() == 192 && bitmap.getHeight() == 192;
                        paths &= !entry.getName().contains("/") && expected.containsKey(
                                entry.getName().substring(0, entry.getName().length() - 4));
                        count++;
                        if (bitmap != null) bitmap.recycle();
                    }
                    check(dimensions, "Reference-compatible flat icon dimensions");
                    check(paths, "Flat package/activity resource path");
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
                boolean dimensions = true;
                for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                    byte[] bytes = icons.readAllBytes();
                    if (entry.getName().equals("icon_mask.png") || entry.getName().equals("transparent_config.xml")) continue;
                    names.add(entry.getName());
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    dimensions &= bitmap != null && bitmap.getWidth() == 192 && bitmap.getHeight() == 192;
                    if (bitmap != null) bitmap.recycle();
                }
                check(dimensions, "Full-pack PNG dimensions");
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
        verifySettingsUi(context);
        verifyInlineStyleFixture(context);
    }

    private void verifyTemplateBackup(Context context) throws Exception {
        File testRoot = new File(context.getCacheDir(), "template-backup-check-" + Long.toUnsignedString(System.nanoTime()));
        File sourceDirectory = new File(testRoot, "source");
        File targetDirectory = new File(testRoot, "target");
        Files.createDirectories(sourceDirectory.toPath());
        Files.createDirectories(targetDirectory.toPath());
        Context sourceContext = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return sourceDirectory; }
        };
        Context targetContext = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return targetDirectory; }
        };
        Bitmap image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.rgb(210, 80, 40));
        try {
            String reference = FileIcons.save(sourceContext, image);
            File coverFile = new File(sourceDirectory, "backup-cover.jpg");
            File wallpaperFile = new File(sourceDirectory, "backup-wallpaper.jpg");
            try (FileOutputStream output = new FileOutputStream(coverFile)) {
                check(image.compress(Bitmap.CompressFormat.JPEG, 95, output), "Backup test cover encoded");
            }
            try (FileOutputStream output = new FileOutputStream(wallpaperFile)) {
                check(image.compress(Bitmap.CompressFormat.JPEG, 95, output), "Backup test wallpaper encoded");
            }
            byte[] expectedIcon = FileIcons.bytes(sourceContext, reference);
            byte[] expectedCover = Files.readAllBytes(coverFile.toPath());
            byte[] expectedWallpaper = Files.readAllBytes(wallpaperFile.toPath());

            TemplateStore source = new TemplateStore(sourceContext);
            Draft withAssets = new Draft();
            withAssets.packs.add("dev.test.backup");
            withAssets.title = "Template with media";
            withAssets.tree = "content://device-specific/tree";
            withAssets.coverPath = coverFile.getName();
            withAssets.wallpaperPath = wallpaperFile.getName();
            withAssets.overlay = reference;
            withAssets.background = reference;
            withAssets.shape = IconShape.FILE;
            withAssets.shapeFile = reference;
            withAssets.overrides.put("dev.test.app/dev.test.app.Main", reference);
            TemplateStore.Entry first = source.save(withAssets, "Backup test pack", true);
            Draft secondDraft = new Draft();
            secondDraft.packs.add("dev.test.backup");
            secondDraft.title = "Second template";
            source.save(secondDraft, "Backup test pack", true);
            byte[] firstBackup = source.backup();
            org.json.JSONArray portableTemplates = new org.json.JSONObject(
                    new String(firstBackup, StandardCharsets.UTF_8)).getJSONArray("templates");
            org.json.JSONObject portableFirst = null;
            for (int i = 0; i < portableTemplates.length(); i++) {
                org.json.JSONObject candidate = portableTemplates.getJSONObject(i);
                if (candidate.getString("id").equals(first.id())) portableFirst = candidate;
            }
            if (portableFirst == null) throw new AssertionError("Backup fixture template is missing");
            org.json.JSONObject portableSettings = portableFirst.getJSONObject("settings");
            check(portableSettings.getString("tree").isEmpty() && portableSettings.getString("coverPath").isEmpty()
                            && portableSettings.getString("wallpaperPath").isEmpty(),
                    "Backup excludes device-specific folders and local media paths");

            TemplateStore target = new TemplateStore(targetContext);
            TemplateStore.MergeResult initial = target.restoreBackup(firstBackup);
            check(initial.imported() == 2 && initial.retained() == 0 && target.list().size() == 2,
                    "Backup imports every template");
            Draft restored = target.open(first.id());
            check(restored.tree.isEmpty() && restored.shape == IconShape.FILE
                            && restored.overlay.equals(reference) && restored.background.equals(reference)
                            && restored.shapeFile.equals(reference),
                    "Restore keeps media references and clears the device-specific export folder");
            check(java.util.Arrays.equals(expectedIcon, FileIcons.bytes(targetContext, reference))
                            && java.util.Arrays.equals(expectedCover,
                            Files.readAllBytes(new File(targetDirectory, "template-cover.jpg").toPath()))
                            && java.util.Arrays.equals(expectedWallpaper,
                            Files.readAllBytes(new File(targetDirectory, "template-wallpaper.jpg").toPath())),
                    "Backup restores custom icon, cover and wallpaper bytes");

            restored.title = "Edited on this device";
            target.save(restored, "Backup test pack", false);
            Draft localOnly = new Draft();
            localOnly.packs.add("dev.test.backup");
            localOnly.title = "Local-only template";
            TemplateStore.Entry local = target.save(localOnly, "Backup test pack", true);
            Draft thirdDraft = new Draft();
            thirdDraft.packs.add("dev.test.backup");
            thirdDraft.title = "Added later";
            source.save(thirdDraft, "Backup test pack", true);
            TemplateStore.MergeResult merged = target.restoreBackup(source.backup());
            check(merged.imported() == 1 && merged.retained() == 2 && target.list().size() == 4,
                    "Restore adds only missing IDs and preserves local-only templates");
            check(target.open(first.id()).title.equals("Edited on this device")
                            && target.list().stream().anyMatch(entry -> entry.id().equals(local.id())),
                    "A matching ID never overwrites the current template");

            File storeFile = new File(targetDirectory, "templates.json");
            byte[] beforeInvalidRestore = Files.readAllBytes(storeFile.toPath());
            byte[] damaged = source.backup();
            damaged[0] ^= 1;
            expectFailure(() -> target.restoreBackup(damaged), "Damaged backup is rejected");

            org.json.JSONObject invalidMedia = new org.json.JSONObject(new String(source.backup(), StandardCharsets.UTF_8));
            org.json.JSONArray items = invalidMedia.getJSONArray("templates");
            org.json.JSONObject mediaItem = null;
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject candidate = items.getJSONObject(i);
                if (candidate.getJSONObject("fileIcons").length() > 0) {
                    mediaItem = candidate;
                    break;
                }
            }
            if (mediaItem == null) throw new AssertionError("Backup fixture has no file icons");
            mediaItem.put("fileIcons", new org.json.JSONObject());
            String mediaDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(items.toString().getBytes(StandardCharsets.UTF_8)));
            invalidMedia.put("sha256", mediaDigest);
            expectFailure(() -> target.restoreBackup(invalidMedia.toString().getBytes(StandardCharsets.UTF_8)),
                    "Backup with missing media is rejected even with a matching checksum");
            check(java.util.Arrays.equals(beforeInvalidRestore, Files.readAllBytes(storeFile.toPath())),
                    "Rejected backups leave the existing template store byte-for-byte unchanged");
        } finally {
            image.recycle();
            if (testRoot.exists()) {
                try (var paths = Files.walk(testRoot.toPath())) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    private void verifySettingsUi(Context context) throws Exception {
        android.app.Application application = (android.app.Application) context.getApplicationContext();
        java.util.concurrent.atomic.AtomicReference<MainActivity> resumed = new java.util.concurrent.atomic.AtomicReference<>();
        android.app.Application.ActivityLifecycleCallbacks callbacks = new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(android.app.Activity activity) {
                if (activity instanceof MainActivity main) resumed.set(main);
            }
            @Override public void onActivityCreated(android.app.Activity activity, Bundle state) {}
            @Override public void onActivityStarted(android.app.Activity activity) {}
            @Override public void onActivityPaused(android.app.Activity activity) {}
            @Override public void onActivityStopped(android.app.Activity activity) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(android.app.Activity activity) {}
        };
        var preferences = context.getSharedPreferences("settings", 0);
        Map<String, ?> before = preferences.getAll();
        android.app.LocaleManager locales = context.getSystemService(android.app.LocaleManager.class);
        android.os.LocaleList previousLocales = locales.getApplicationLocales();
        String originalDraft = context.getSharedPreferences("draft", 0).getString("json", "");
        application.registerActivityLifecycleCallbacks(callbacks);
        try {
            MainActivity first = (MainActivity) startActivitySync(new android.content.Intent(context, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForWork(first);
            for (String mode : List.of("light", "dark", "system")) {
                MainActivity current = resumed.get();
                if (current == null) current = first;
                MainActivity activity = current;
                String beforeTheme = preferences.getString("theme", "system");
                String desiredLanguage = mode.equals("dark") ? "ru" : "en";
                boolean recreate = !beforeTheme.equals(mode)
                        || !locales.getApplicationLocales().toLanguageTags().equals(desiredLanguage);
                runOnMainChecked(() -> {
                    if (mode.equals("dark")) activity.openEditor();
                });
                waitForWork(activity);
                runOnMainChecked(() -> {
                    try {
                        var method = MainActivity.class.getDeclaredMethod("showSettings");
                        method.setAccessible(true); method.invoke(activity);
                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                    android.app.Dialog dialog = (android.app.Dialog) field(activity, "settingsDialog");
                    android.view.View decor = dialog.getWindow().getDecorView();
                    check(findText(decor, activity.getString(R.string.backup_templates)) != null
                                    && findText(decor, activity.getString(R.string.restore_templates)) != null,
                            "Settings provide both template backup and restore actions");
                    int label = mode.equals("light") ? R.string.theme_light
                            : mode.equals("dark") ? R.string.theme_dark : R.string.theme_system;
                    android.widget.TextView themeChoice = findText(decor, activity.getString(label));
                    check(!(themeChoice instanceof android.widget.RadioButton),
                            "Settings use appearance tiles rather than stock radio buttons");
                    themeChoice.performClick();
                    android.view.View themeTile = (android.view.View) themeChoice.getParent();
                    check(themeTile.isSelected() && themeTile.getStateDescription()
                                    .equals(activity.getString(R.string.option_selected)),
                            "Appearance tile exposes its selected state");
                    android.widget.TextView languageChoice = findText(decor, desiredLanguage.equals("ru") ? "Русский" : "English");
                    languageChoice.performClick();
                    android.view.View languageRow = (android.view.View) languageChoice.getParent();
                    check(languageRow.isSelected()
                                    && languageRow.findViewWithTag("selection").getVisibility() == android.view.View.VISIBLE,
                            "Selected language has a checkmark");
                    android.view.View apply = findText(decor, activity.getString(R.string.apply_settings));
                    android.view.ViewGroup settingsLayout = (android.view.ViewGroup) apply.getParent();
                    check(settingsLayout.getChildAt(1) instanceof android.widget.ScrollView
                                    && settingsLayout.indexOfChild(apply) > 1,
                            "Apply action is outside the scrollable choices");
                    resumed.set(null);
                    apply.performClick();
                });
                long deadline = android.os.SystemClock.uptimeMillis() + 30000;
                while (recreate && (resumed.get() == null || resumed.get() == activity)
                        && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100);
                MainActivity result = recreate ? resumed.get() : activity;
                check(result != null && (!recreate || result != activity), "Settings recreate activity when needed");
                waitForWork(result);
                runOnMainChecked(() -> {
                    check(result.getResources().getConfiguration().getLocales().get(0).getLanguage().equals(desiredLanguage),
                            "Selected app language takes effect after recreation: " + desiredLanguage + " vs "
                                    + result.getResources().getConfiguration().getLocales());
                    check(App.text(R.string.settings).equals(result.getString(R.string.settings)),
                            "Background errors and activity UI use the same selected language");
                    boolean dark = (result.getResources().getConfiguration().uiMode
                            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                    if (!mode.equals("system")) check(dark == mode.equals("dark"), "Appearance applies in the real activity");
                    check((int) field(result, "INK") != (int) field(result, "BG"), "Foreground contrasts with background");
                    if (mode.equals("dark")) check((boolean) field(result, "editing"),
                            "Changing settings preserves the open draft editor");
                });
                if (mode.equals("dark")) {
                    verifyStyleUi(result);
                    verifyShapePickerUi(result);
                    verifyIconScaleUi(result);
                    verifyOverlayUi(result);
                    verifyBackgroundUi(result);
                    verifyFiltersUi(result);
                    verifyPositionUi(result);
                }
                if (mode.equals("light")) {
                    runOnMainChecked(() -> { if (!(boolean) field(result, "editing")) result.openEditor(); });
                    waitForWork(result);
                    runOnMainChecked(() -> {
                        @SuppressWarnings("unchecked")
                        List<android.widget.Button> navigation =
                                (List<android.widget.Button>) field(result, "editorTabs");
                        android.view.View bar = (android.view.View) navigation.get(0).getParent();
                        android.widget.LinearLayout.LayoutParams params =
                                (android.widget.LinearLayout.LayoutParams) bar.getLayoutParams();
                        check(params.bottomMargin >= Math.round(8 * result.getResources().getDisplayMetrics().density),
                                "Main navigation keeps a bottom safety gap in light mode");
                        for (android.widget.Button tab : navigation) {
                            check(tab.getBottom() <= bar.getHeight() - bar.getPaddingBottom(),
                                    "Main navigation tab fits above its bottom padding in light mode");
                            check(tab.getStateListAnimator() == null && tab.getElevation() == 0
                                            && tab.getTranslationZ() == 0 && tab.getMinWidth() == 0
                                            && tab.getMaxLines() == 2 && tab.getLayoutParams().height == -2,
                                    "Main navigation adapts to labels without clipped light-theme shadows");
                        }
                    });
                }
            }
        } finally {
            runOnMainChecked(() -> {
                MainActivity current = resumed.get();
                if (current != null) current.finish();
            });
            var edit = preferences.edit().clear();
            for (var entry : before.entrySet()) edit.putString(entry.getKey(), (String) entry.getValue());
            edit.commit();
            runOnMainSync(() -> locales.setApplicationLocales(previousLocales));
            application.unregisterActivityLifecycleCallbacks(callbacks);
            check(context.getSharedPreferences("draft", 0).getString("json", "").equals(originalDraft),
                    "Settings changes preserve the user draft exactly");
        }
    }

    private interface CheckedAction { void run() throws Exception; }

    private void runOnMainChecked(CheckedAction action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failed = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failed.set(error); }
        });
        if (failed.get() != null) throw new AssertionError(failed.get());
    }

    private void verifyStyleUi(MainActivity activity) throws Exception {
        runOnMainChecked(() -> {
            @SuppressWarnings("unchecked")
            List<android.widget.Button> tabs = (List<android.widget.Button>) field(activity, "editorTabs");
            tabs.get(1).performClick();
        });
        waitForThumbnails(activity);
        runOnMainChecked(() -> {
            android.widget.ImageView preview = (android.widget.ImageView) field(activity, "stylePreview");
            check(preview.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                            && activity.getString(R.string.style_preview).contentEquals(preview.getContentDescription()),
                    "Style tab shows its universal six-icon preview");
            check(preview.getScaleType() == android.widget.ImageView.ScaleType.CENTER_CROP
                            && preview.getHeight() > 0,
                    "Universal style preview fits the available space without stretching");
            @SuppressWarnings("unchecked")
            List<android.view.View> pages = (List<android.view.View>) field(activity, "editorPages");
            android.view.ViewGroup page = (android.view.ViewGroup) pages.get(1);
            android.view.View stage = page.findViewWithTag("style-stage");
            android.view.View tabs = page.findViewWithTag("style-tabs");
            android.widget.ScrollView scroll = page.findViewWithTag("style-scroll");
            check(stage.getParent() == page && tabs.getParent() == scroll.getParent()
                            && ((android.view.View) tabs.getParent()).getParent() == page
                            && preview.getParent() == stage,
                    "Style preview and inner tabs are pinned above the scrolling controls");
            int pinnedTop = stage.getTop();
            scroll.fullScroll(android.view.View.FOCUS_DOWN);
            check(stage.getTop() == pinnedTop && preview.isShown(),
                    "Scrolling style controls does not move or hide the preview");
            check(findText(page, activity.getString(R.string.cover)) == null
                            && findText(page, activity.getString(R.string.wallpaper)) == null
                            && findText(pages.get(0), activity.getString(R.string.cover)) != null
                            && findText(pages.get(0), activity.getString(R.string.wallpaper)) != null,
                    "Cover and wallpaper belong to Basics rather than Style");
            @SuppressWarnings("unchecked")
            List<android.widget.Button> innerTabs = (List<android.widget.Button>) field(activity, "styleTabs");
            @SuppressWarnings("unchecked")
            List<android.widget.LinearLayout> sections =
                    (List<android.widget.LinearLayout>) field(activity, "styleSections");
            check(innerTabs.size() == 4 && sections.size() == 4,
                    "Style has Background, Overlay, Icon options and Filters tabs");
            @SuppressWarnings("unchecked")
            List<android.widget.Button> navigation = (List<android.widget.Button>) field(activity, "editorTabs");
            android.view.View navigationBar = navigation.get(0).getParent() instanceof android.view.View view ? view : null;
            android.widget.LinearLayout.LayoutParams navigationParams =
                    (android.widget.LinearLayout.LayoutParams) navigationBar.getLayoutParams();
            check(navigationParams.bottomMargin >= Math.round(8 * activity.getResources().getDisplayMetrics().density),
                    "Main navigation keeps a bottom safety gap");
            for (android.widget.Button tab : navigation)
                check(tab.getBottom() <= navigationBar.getHeight() - navigationBar.getPaddingBottom(),
                        "Main navigation tab fits above its bottom padding");
            for (int i = 0; i < innerTabs.size(); i++) {
                innerTabs.get(i).performClick();
                for (int section = 0; section < sections.size(); section++) {
                    check(sections.get(section).isShown() == (section == i)
                                    && innerTabs.get(section).isSelected() == (section == i),
                            "Style tab selects exactly one settings section: " + i);
                }
                check(preview.isShown(), "Style preview remains visible on inner tab " + i);
            }
        });
        verifyThemePreviewUi(activity);
    }

    private void verifyThemePreviewUi(MainActivity activity) throws Exception {
        Draft draft = (Draft) field(activity, "draft");
        String originalWallpaper = draft.wallpaperPath;
        boolean originalSystemIcons = draft.systemIcons;
        List<?> apps = (List<?>) field(activity, "apps");
        check(!apps.isEmpty(), "Theme preview has installed apps to render");
        List<IconRepository.App> sortedApps = new ArrayList<>();
        for (Object app : apps) sortedApps.add((IconRepository.App) app);
        var collator = java.text.Collator.getInstance(
                activity.getResources().getConfiguration().getLocales().get(0));
        sortedApps.sort(java.util.Comparator.comparing(IconRepository.App::label, collator)
                .thenComparing(IconRepository.App::key));
        IconRepository.App fallbackApp = sortedApps.get(0);
        boolean hadFallbackOverride = draft.overrides.containsKey(fallbackApp.key());
        String originalFallbackOverride = draft.overrides.get(fallbackApp.key());
        String previewWallpaper = "theme-preview-test.png";
        File wallpaperFile = new File(activity.getFilesDir(), previewWallpaper);
        Bitmap source = Bitmap.createBitmap(48, 72, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.MAGENTA);
        try {
            try (FileOutputStream output = new FileOutputStream(wallpaperFile)) {
                check(source.compress(Bitmap.CompressFormat.PNG, 100, output),
                        "Theme preview wallpaper fixture encoded");
            }
            String originalDraft = draft.toJson().toString();
            runOnMainChecked(() -> {
                draft.wallpaperPath = previewWallpaper;
                draft.systemIcons = false;
                draft.overrides.put(fallbackApp.key(), "");
                android.widget.LinearLayout header = (android.widget.LinearLayout)
                        ((android.widget.LinearLayout) field(activity, "root")).getChildAt(0);
                check(header.getChildAt(1).getTag().equals("theme-preview-button")
                                && header.getChildAt(2) instanceof android.widget.Button
                                && ((android.widget.Button) header.getChildAt(2)).getText()
                                .equals(activity.getString(R.string.templates)),
                        "Eye preview action sits immediately before Templates");
                header.getChildAt(1).performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "themePreviewDialog");
                android.view.View decor = dialog.getWindow().getDecorView();
                check(findText(decor, activity.getString(R.string.theme_preview)) != null
                                && decor.findViewWithTag("theme-preview-close") instanceof android.widget.Button,
                        "Preview has a shared heading and Close action");
                android.widget.GridLayout grid = decor.findViewWithTag("theme-preview-page-0");
                check(grid != null && grid.getRowCount() == 6 && grid.getColumnCount() == 5
                                && grid.getChildCount() == 30,
                        "Theme preview page contains exactly five columns and six rows");
                int labelHeight = Math.round(34 * activity.getResources().getDisplayMetrics().density);
                boolean iconsAligned = true;
                for (int i = 0; i < 5; i++) {
                    android.view.ViewGroup tile = (android.view.ViewGroup) grid.getChildAt(i);
                    android.view.View icon = tile.getChildAt(0);
                    android.view.View label = tile.getChildAt(1);
                    iconsAligned &= icon.getTop() == 0
                            && label.getLayoutParams().height == labelHeight
                            && ((android.widget.TextView) label).getMaxLines() == 2;
                }
                check(iconsAligned, "Icon positions remain fixed with a two-line label slot");
                android.view.ViewGroup fallbackTile = decor.findViewWithTag(
                        "theme-preview-app-" + fallbackApp.key());
                android.widget.ImageView fallbackIcon =
                        fallbackTile == null ? null : (android.widget.ImageView) fallbackTile.getChildAt(0);
                check(fallbackIcon != null && fallbackIcon.getDrawable()
                                instanceof android.graphics.drawable.BitmapDrawable,
                        "Apps without a replacement icon render their styled system icon when system icons are off");
                android.widget.HorizontalScrollView scroll = decor.findViewWithTag("theme-preview-scroll");
                android.view.ViewGroup pages = (android.view.ViewGroup) scroll.getChildAt(0);
                check(scroll.isHorizontalScrollBarEnabled() == false && pages.getChildCount() >= 1
                                && grid.getWidth() == scroll.getWidth(),
                        "Theme preview pages use the full horizontal viewport");
                if (apps.size() > 30) check(pages.getChildCount() > 1
                                && pages.getWidth() > scroll.getWidth(),
                        "Apps beyond the first thirty are reachable by horizontal scrolling");
                android.widget.ImageView wallpaper = decor.findViewWithTag(previewWallpaper);
                check(wallpaper != null, "Preview retains the wallpaper draft path on its image view");
                check(wallpaper.getDrawable() instanceof android.graphics.drawable.BitmapDrawable,
                        "Preview loads its selected wallpaper bitmap");
                dialog.dismiss();
                draft.wallpaperPath = originalWallpaper;
                draft.systemIcons = originalSystemIcons;
                if (hadFallbackOverride) draft.overrides.put(fallbackApp.key(), originalFallbackOverride);
                else draft.overrides.remove(fallbackApp.key());
                check(draft.toJson().toString().equals(originalDraft),
                        "Opening and closing the preview leaves the draft unchanged");
            });
        } finally {
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "themePreviewDialog");
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                draft.wallpaperPath = originalWallpaper;
                draft.systemIcons = originalSystemIcons;
                if (hadFallbackOverride) draft.overrides.put(fallbackApp.key(), originalFallbackOverride);
                else draft.overrides.remove(fallbackApp.key());
            });
            source.recycle();
            Files.deleteIfExists(wallpaperFile.toPath());
        }
    }

    private void verifyThemePreviewUiOnly(Context context) throws Exception {
        MainActivity activity = (MainActivity) startActivitySync(new android.content.Intent(context, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            runOnMainChecked(activity::openEditor);
            waitForWork(activity);
            verifyThemePreviewUi(activity);
        } finally {
            runOnMainChecked(activity::finish);
        }
    }

    private void verifyInlineStyleFixture(Context context) throws Exception {
        IconRepository repository = new IconRepository(context);
        IconRepository.Pack pack = repository.load("com.maxpatchs.premium");
        List<IconRepository.App> installed = repository.apps();
        String icon = pack.icons.keySet().iterator().next();
        Map<String, String> allAssigned = new LinkedHashMap<>();
        for (IconRepository.App app : installed) allAssigned.put(app.key(), icon);
        try (ShapePreview scene = ShapePreview.loadStyle(context, installed, pack, allAssigned,
                pack.fallbackSize(), "", "", "", "", List.of())) {
            check(((List<?>) field(scene, "originals")).size() == 3, "Fully covered pack still shows three system examples");
        }
        Map<String, String> noneAssigned = new LinkedHashMap<>();
        for (IconRepository.App app : installed) noneAssigned.put(app.key(), "");
        try (ShapePreview scene = ShapePreview.loadStyle(context, installed, pack, noneAssigned,
                pack.fallbackSize(), "", "", "", "", List.of())) {
            check(((List<?>) field(scene, "packIcons")).size() == 3, "Catalogue fills missing installed pack examples");
        }
        for (Map<String, String> assigned : List.of(allAssigned, noneAssigned)) {
            try (ShapePreview normal = ShapePreview.loadStyle(context, installed, pack, assigned,
                    pack.fallbackSize(), "", "", "", "", List.of());
                 ShapePreview filters = ShapePreview.loadStyle(context, installed, pack, assigned,
                         pack.fallbackSize(), "", "", "", "", List.of(), true)) {
                List<?> originals = (List<?>) field(normal, "originals");
                List<?> filtered = (List<?>) field(filters, "originals");
                for (int i = 0; i < 3; i++)
                    check(((Bitmap) originals.get(i)).sameAs((Bitmap) filtered.get(i)),
                            "Switching to Filters leaves the first system row unchanged: " + i);
            }
        }
        MainActivity activity = (MainActivity) startActivitySync(new android.content.Intent(context, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Draft original = null;
        Bitmap before = null;
        java.util.Set<String> beforeFiles = new java.util.HashSet<>();
        File[] existing = FileIcons.directory(context).listFiles();
        if (existing != null) for (File file : existing) beforeFiles.add(file.getName());
        Bitmap layer = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        android.graphics.Paint layerPaint = new android.graphics.Paint();
        layerPaint.setColor(Color.argb(160, 30, 190, 240));
        new android.graphics.Canvas(layer).drawRect(24, 24, 168, 96, layerPaint);
        String layerRef;
        try { layerRef = FileIcons.save(context, layer); } finally { layer.recycle(); }
        try {
            waitForWork(activity);
            original = (Draft) field(activity, "draft");
            Draft fixture = Draft.fromJson(original.toJson());
            fixture.packs.clear(); fixture.packs.add(pack.packageName);
            fixture.shape = IconShape.CIRCLE; fixture.shapeFile = ""; fixture.maskScale = 100;
            fixture.background = layerRef; fixture.overlay = layerRef; fixture.filters.clear(); fixture.overrides.clear();
            fixture.backgroundAll = true; fixture.overlayAll = true;
            fixture.backgroundScale = 100; fixture.overlayScale = 100;
            fixture.iconScale = 100;
            var build = MainActivity.class.getDeclaredMethod("buildScreen");
            build.setAccessible(true);
            runOnMainChecked(() -> {
                setField(activity, "draft", fixture);
                setField(activity, "pack", pack);
                setField(activity, "apps", installed);
                setField(activity, "editing", true);
                build.invoke(activity);
            });
            waitForThumbnails(activity);
            verifyStyleUi(activity);
            runOnMainChecked(() -> {
                activity.getWindow().getDecorView().findViewWithTag("style-tab-2").performClick();
                android.widget.ImageView preview = (android.widget.ImageView) field(activity, "stylePreview");
                check(activity.getWindow().getDecorView().findViewWithTag("style-icon-scale").isShown(),
                        "Icon scale belongs to inline settings");
                android.view.View decor = activity.getWindow().getDecorView();
                check(decor.findViewWithTag("style-shape-presets").isShown()
                                && !decor.findViewWithTag("style-shape-editor").isShown()
                                && !decor.findViewWithTag("style-shape-file").isShown(),
                        "Shape presets retain the illustrated grid with separate editor and PNG tabs");
                check(((android.view.ViewGroup) decor.findViewWithTag("style-shape-presets")).getChildCount() == 3,
                        "Ten illustrated shape choices occupy three grid rows");
                android.widget.LinearLayout presets = decor.findViewWithTag("style-shape-presets");
                check(presets.getTop() >= Math.round(8 * context.getResources().getDisplayMetrics().density),
                        "Preset grid has a gap below its tabs");
                decor.findViewWithTag("style-shape-tab-file").performClick();
                check(decor.findViewWithTag("style-shape-file").isShown()
                                && !decor.findViewWithTag("style-shape-presets").isShown()
                                && fixture.shape == IconShape.CIRCLE,
                        "PNG tab exposes file controls without applying a missing mask");
                decor.findViewWithTag("style-shape-tab-editor").performClick();
                check(decor.findViewWithTag("style-corner-0").isShown(),
                        "Corner editor is visible on its own inline tab");
            });
            waitForThumbnails(activity);
            before = ((android.graphics.drawable.BitmapDrawable) ((android.widget.ImageView) field(activity,
                    "stylePreview")).getDrawable()).getBitmap().copy(Bitmap.Config.ARGB_8888, false);
            runOnMainChecked(() -> activity.getWindow().getDecorView()
                    .findViewWithTag("style-icon-scale-decrease").performClick());
            waitForThumbnails(activity);
            check(fixture.iconScale == 99 && Draft.load(context).iconScale == 99, "Inline icon scale is saved immediately");
            Bitmap after = ((android.graphics.drawable.BitmapDrawable) ((android.widget.ImageView) field(activity,
                    "stylePreview")).getDrawable()).getBitmap();
            check(!before.sameAs(after), "Inline icon scale updates the pinned scene");
            before.recycle();
            before = after.copy(Bitmap.Config.ARGB_8888, false);
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                decor.findViewWithTag("style-tab-0").performClick();
                check(decor.findViewWithTag("style-background-scale").isShown(), "Background scale is inline");
                decor.findViewWithTag("style-background-scale-decrease").performClick();
            });
            waitForThumbnails(activity);
            check(fixture.backgroundScale == 99 && Draft.load(context).backgroundScale == 99,
                    "Background scale saves without modal confirmation");
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                decor.findViewWithTag("style-tab-1").performClick();
                check(decor.findViewWithTag("style-overlay-scale").isShown(), "Overlay scale is inline");
                decor.findViewWithTag("style-overlay-scale-increase").performClick();
            });
            waitForThumbnails(activity);
            check(fixture.overlayScale == 101 && Draft.load(context).overlayScale == 101,
                    "Overlay scale saves without modal confirmation");
            after = ((android.graphics.drawable.BitmapDrawable) ((android.widget.ImageView) field(activity,
                    "stylePreview")).getDrawable()).getBitmap();
            check(!before.sameAs(after), "Layer edits update the same pinned preview");
            runOnMainChecked(() -> activity.getWindow().getDecorView().findViewWithTag("style-tab-2").performClick());
            runOnMainChecked(() -> activity.getWindow().getDecorView()
                    .findViewWithTag("style-corner-0-decrease").performClick());
            waitForThumbnails(activity);
            check(fixture.shape == IconShape.CUSTOM, "Inline corner arrows select custom shape immediately");
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                check(decor.findViewWithTag("style-preview-loading") == null,
                        "Changing style never overlays a loading spinner");
                decor.findViewWithTag("style-tab-3").performClick();
                check(decor.findViewWithTag("filter-add").isShown()
                                && decor.findViewWithTag("filters") == null,
                        "Filters have their own inline tab, not a modal launch button");
                decor.findViewWithTag("filter-add").performClick();
                android.app.AlertDialog chooser = (android.app.AlertDialog) field(activity, "filterTypeDialog");
                chooser.getListView().performItemClick(null, 0, 0);
                decor.findViewWithTag("filter-0-brightness-increase").performClick();
                check(Draft.load(context).filters.size() == 1
                                && ((IconFilters.ColorCorrection) Draft.load(context).filters.get(0)).values[0] == 1,
                        "Inline filter creation and slider edits save immediately");
            });
            waitForThumbnails(activity);
            for (boolean filtersMode : new boolean[]{true, false}) {
                runOnMainChecked(() -> activity.getWindow().getDecorView()
                        .findViewWithTag(filtersMode ? "style-tab-3" : "style-tab-0").performClick());
                waitForThumbnails(activity);
                android.widget.ImageView preview = (android.widget.ImageView) field(activity, "stylePreview");
                Bitmap actual = ((android.graphics.drawable.BitmapDrawable) preview.getDrawable()).getBitmap();
                check(preview.getScaleType() == android.widget.ImageView.ScaleType.CENTER_CROP,
                        "Preview background fills the stage without letterboxing");
                try (ShapePreview scene = ShapePreview.loadStyle(context, installed, pack, fixture.overrides,
                        pack.fallbackSize(), fixture.wallpaperPath, fixture.overlay, fixture.background,
                        "", fixture.filters, filtersMode)) {
                    if (filtersMode) check(((List<?>) field(scene, "originals")).size() == 6
                                    && ((List<?>) field(scene, "packIcons")).isEmpty(),
                            "Filters use six system originals across both rows");
                    Bitmap expected = scene.renderStyle(fixture.shape, fixture.corners, fixture.overlayScale,
                            fixture.overlayClip, fixture.backgroundScale, fixture.backgroundClip, null,
                            fixture.maskScale, fixture.overlayAll, fixture.backgroundAll, fixture.iconScale,
                            !filtersMode);
                    try { check(actual.sameAs(expected), filtersMode
                            ? "Filters tab renders both rows as system icons with live effects"
                            : "Leaving Filters restores pack icons in the second row"); }
                    finally { expected.recycle(); }
                }
            }
            runOnMainChecked(() -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            android.os.SystemClock.sleep(800);
            waitForIdleSync();
            runOnMainChecked(() -> {
                android.view.View stage = activity.getWindow().getDecorView().findViewWithTag("style-stage");
                android.view.View scroll = activity.getWindow().getDecorView().findViewWithTag("style-scroll");
                check(stage.getWidth() <= ((android.view.View) stage.getParent()).getWidth() / 3 + 1
                                && stage.getHeight() > 0
                                && scroll.getHeight() >= 48 * context.getResources().getDisplayMetrics().density,
                        "Landscape preview leaves usable space for scrolling controls: stage=" + stage.getHeight()
                                + ", page=" + ((android.view.View) stage.getParent()).getHeight() + ", scroll=" + scroll.getHeight());
                check(field(activity, "shapeDialog") == null && field(activity, "backgroundDialog") == null,
                        "Inline settings do not open old modal editors");
            });
        } finally {
            if (before != null) before.recycle();
            Draft restored = original;
            runOnMainChecked(() -> {
                if (restored != null) { setField(activity, "draft", restored); restored.save(activity); }
                activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                activity.finish();
            });
            String layerName = layerRef.substring("fileicon:".length()) + ".png";
            if (!beforeFiles.contains(layerName))
                Files.deleteIfExists(new File(FileIcons.directory(context), layerName).toPath());
        }
    }

    private void verifyShapeExamples(Context context, IconRepository.Pack pack) throws Exception {
        Bitmap expectedSoftSquare = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        android.graphics.Path expectedPath = new android.graphics.Path();
        expectedPath.addRoundRect(0, 0, 1, 1, .22f / 2, .22f / 2, android.graphics.Path.Direction.CW);
        android.graphics.Matrix transform = new android.graphics.Matrix();
        transform.setScale(176, 176);
        transform.postTranslate(8, 8);
        expectedPath.transform(transform);
        android.graphics.Paint silhouette = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        silhouette.setColor(android.graphics.Color.BLACK);
        new android.graphics.Canvas(expectedSoftSquare).drawPath(expectedPath, silhouette);
        Bitmap actualSoftSquare = IconShape.SQUARE.mask(176);
        try { check(actualSoftSquare.sameAs(expectedSoftSquare), "Soft square uses exactly half the rounded square corner radius"); }
        finally { actualSoftSquare.recycle(); expectedSoftSquare.recycle(); }
        IconRepository.Pack sparse = new IconRepository.Pack(pack.packageName, pack.label, pack.resources,
                pack.badge, pack.manager);
        List<IconRepository.App> apps = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            String label = Character.toString('A' + i);
            apps.add(new IconRepository.App(new ComponentName("dev.test." + label.toLowerCase(java.util.Locale.ROOT),
                    "dev.test.Main"), label));
        }
        String icon = pack.icons.keySet().iterator().next();
        sparse.icons.put(icon, pack.icons.get(icon));
        List<String> alphabetical = List.of("A", "B", "C", "D", "E", "F");
        var selected = ShapePreview.examples(apps, sparse, Map.of(), java.util.Locale.ENGLISH);
        check(selected.size() == 6 && selected.stream().map(IconRepository.App::label).toList().equals(alphabetical),
                "Shape preview selects exactly six unthemed apps alphabetically");
        for (IconRepository.App app : apps) if (app.label().equals("A") || app.label().equals("C"))
            sparse.mappings.put(app.key(), icon);
        String manuallyAssigned = apps.stream().filter(app -> app.label().equals("E")).findFirst().orElseThrow().key();
        selected = ShapePreview.examples(apps, sparse, Map.of(manuallyAssigned, icon), java.util.Locale.ENGLISH);
        check(selected.stream().map(IconRepository.App::label).toList().equals(List.of("B", "D", "F", "G", "H", "A")),
                "Preview prefers unthemed apps and fills remaining slots without duplicates");
        for (IconRepository.App app : apps) sparse.mappings.put(app.key(), icon);
        selected = ShapePreview.examples(apps, sparse, Map.of(), java.util.Locale.ENGLISH);
        check(selected.stream().map(IconRepository.App::label).toList().equals(alphabetical),
                "Fully themed apps still provide six alphabetical system icons");
        check(ShapePreview.examples(apps.subList(0, 3), sparse, Map.of(), java.util.Locale.ENGLISH).size() == 3,
                "Small app lists are not padded with duplicate icons");
        List<IconRepository.App> installed = new IconRepository(context).apps();
        List<IconRepository.App> actual = ShapePreview.examples(installed, pack, Map.of(), java.util.Locale.ENGLISH);
        try (ShapePreview scene = ShapePreview.loadStyle(context, installed, pack, Map.of(), pack.fallbackSize(),
                "", "", "", "", List.of())) {
            check(((List<?>) field(scene, "originals")).size() == 3
                            && ((List<?>) field(scene, "packIcons")).size() == 3,
                    "Universal style preview loads three system icons and three pack replacements");
            Bitmap rendered = scene.renderStyle(IconShape.ROUNDED_SQUARE, null, 100, true,
                    100, true, null, 100, false, false, 100);
            try {
                check(rendered.getWidth() == ShapePreview.WIDTH && rendered.getHeight() == ShapePreview.HEIGHT,
                        "Universal style preview keeps the established two-row scene geometry");
            } finally { rendered.recycle(); }
        }
        File wallpaper = new File(context.getFilesDir(), "shape-preview-test.png");
        Bitmap solid = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888);
        solid.eraseColor(android.graphics.Color.MAGENTA);
        try {
            try (FileOutputStream output = new FileOutputStream(wallpaper)) {
                check(solid.compress(Bitmap.CompressFormat.PNG, 100, output), "Test preview wallpaper encoded");
            }
            try (ShapePreview scene = ShapePreview.load(context, actual, pack.fallbackSize(), wallpaper.getName(), "")) {
                Bitmap rendered = scene.render(IconShape.HEART);
                try {
                    check(rendered.getWidth() == ShapePreview.WIDTH && rendered.getHeight() == ShapePreview.HEIGHT
                                    && rendered.getPixel(0, 0) == android.graphics.Color.MAGENTA
                                    && rendered.getPixel(719, 439) == android.graphics.Color.MAGENTA,
                            "Six-icon scene uses template wallpaper with an edge-to-edge background");
                } finally { rendered.recycle(); }
            }
            try (ShapePreview scene = ShapePreview.load(context, actual, pack.fallbackSize(), "", "")) {
                Bitmap rendered = scene.render(IconShape.CIRCLE);
                try {
                    check(rendered.getPixel(0, 0) != rendered.getPixel(719, 439)
                                    && android.graphics.Color.alpha(rendered.getPixel(0, 0)) == 255,
                            "Missing template wallpaper uses an opaque preview-only gradient");
                } finally { rendered.recycle(); }
            }
            expectFailure(() -> ShapePreview.load(context, actual, 176, "missing-preview-wallpaper.png", ""),
                    "Missing selected wallpaper produces an explicit error, not a fallback");
        } finally { solid.recycle(); Files.deleteIfExists(wallpaper.toPath()); }
        for (IconShape shape : IconShape.values()) {
            Draft draft = new Draft();
            draft.shape = shape;
            if (shape == IconShape.FILE) draft.shapeFile = "fileicon:i" + "0".repeat(64);
            check(Draft.fromJson(draft.toJson()).shape == shape, "Every shape retains its stable template identifier");
        }
        Draft custom = new Draft();
        check(!custom.toJson().has("corners") && !custom.toJson().has("overlayClip") && custom.overlayClip,
                "Preset drafts keep legacy fingerprints and clip overlays by default");
        custom.shape = IconShape.CUSTOM;
        custom.corners = new int[]{0, 25, 75, 100};
        check(java.util.Arrays.equals(Draft.fromJson(custom.toJson()).corners, custom.corners),
                "Custom corner radii round-trip through templates");
        expectFailure(() -> Draft.fromJson(custom.toJson().put("corners", new org.json.JSONArray(List.of(0, 1, 101, 2)))),
                "Corner radius above 100 percent is rejected");
        expectFailure(() -> Draft.fromJson(custom.toJson().put("corners", new org.json.JSONArray(List.of(0, 1, 2)))),
                "Corner list must contain exactly four values");
        custom.overlay = "fileicon:test";
        custom.overlayClip = false;
        check(!Draft.fromJson(custom.toJson()).overlayClip, "Disabled overlay clipping round-trips");
        Bitmap round = IconShape.CUSTOM.mask(172, new int[]{100, 100, 100, 100});
        Bitmap circle = IconShape.CIRCLE.mask(172);
        Bitmap sharp = IconShape.CUSTOM.mask(172, new int[]{0, 0, 0, 0});
        try {
            int different = 0;
            for (int y = 0; y < 192; y++) for (int x = 0; x < 192; x++) {
                if (Math.abs(android.graphics.Color.alpha(round.getPixel(x, y))
                        - android.graphics.Color.alpha(circle.getPixel(x, y))) > 128) different++;
            }
            check(different < 40, "All four corners at 100 percent produce a circle");
            check(android.graphics.Color.alpha(sharp.getPixel(10, 10)) == 255
                            && android.graphics.Color.alpha(sharp.getPixel(181, 181)) == 255,
                    "All corners at 0 percent produce a sharp square");
        } finally { round.recycle(); circle.recycle(); sharp.recycle(); }
    }

    private void verifyShapePickerUi(MainActivity activity) throws Exception {
        Draft original = (Draft) field(activity, "draft");
        Draft isolated = Draft.fromJson(original.toJson());
        isolated.filters.clear();
        isolated.wallpaperPath = "";
        isolated.shape = IconShape.ROUNDED_SQUARE;
        isolated.overlay = "";
        isolated.overlayScale = 100;
        isolated.overlayClip = true;
        isolated.background = "";
        isolated.shapeFile = "";
        String savedJson = activity.getSharedPreferences("draft", 0).getString("json", "");
        var build = MainActivity.class.getDeclaredMethod("buildScreen");
        build.setAccessible(true);
        Bitmap[] universalBefore = {null};
        try {
            runOnMainChecked(() -> {
                setField(activity, "draft", isolated);
                build.invoke(activity);
                ((android.view.View) field(activity, "shapeButton")).performClick();
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                check(dialog != null && dialog.isShowing(), "Shape popup opens from the editor");
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                android.view.View decor = dialog.getWindow().getDecorView();
                check(decor.findViewWithTag("overlay-scale") == null,
                        "Shape popup has no scale slider when no overlay is selected");
                android.widget.ImageView scene = decor.findViewWithTag("shape-scene");
                check(scene.getDrawable() instanceof android.graphics.drawable.BitmapDrawable,
                        "Shape popup loads its six-icon wallpaper scene before enabling Apply");
                check(Math.abs(scene.getWidth() * (float) ShapePreview.HEIGHT / ShapePreview.WIDTH - scene.getHeight()) <= 1,
                        "Wallpaper scene preserves square icon proportions without stretching");
                android.widget.ImageView universal = (android.widget.ImageView) field(activity, "stylePreview");
                check(universal.getDrawable() instanceof android.graphics.drawable.BitmapDrawable,
                        "Universal preview is loaded alongside the shape dialog");
                universalBefore[0] = ((android.graphics.drawable.BitmapDrawable) universal.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                Bitmap initial = ((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                try {
                    check(decor.findViewWithTag(IconShape.ROUNDED_SQUARE).isSelected(),
                            "Popup initially selects the saved shape");
                    check(decor.findViewWithTag("overlay-clip") == null,
                            "Shape popup has no overlay clip toggle without an overlay");
                    check(decor.findViewWithTag("shape-editor").getVisibility() != android.view.View.VISIBLE
                                    && decor.findViewWithTag("shape-tab-presets").isSelected(),
                            "Preset shapes tab opens first for a preset shape");
                    android.view.View fileTab = decor.findViewWithTag("shape-tab-file");
                    for (String tag : List.of("shape-tab-presets", "shape-tab-editor", "shape-tab-file")) {
                        android.view.View tab = decor.findViewWithTag(tag);
                        check(tab.getStateListAnimator() == null && tab.getElevation() == 0 && tab.getTranslationZ() == 0,
                                "Shape selector tabs have no resting or pressed elevation: " + tag);
                        check(((android.widget.Button) tab).getMinWidth() == 0
                                        && ((android.widget.Button) tab).getMaxLines() == 2,
                                "Shape selector tabs fit narrow layouts without clipping their labels: " + tag);
                    }
                    check(fileTab != null, "Shape popup offers a PNG mask tab");
                    fileTab.performClick();
                    check(decor.findViewWithTag("shape-file").getVisibility() == android.view.View.VISIBLE
                                    && findText(decor, activity.getString(R.string.choose_shape_file)) != null,
                            "PNG mask tab explains and exposes file selection");
                    android.view.View fileHelp = decor.findViewWithTag("help-shape_file_help");
                    check(((android.widget.LinearLayout.LayoutParams) ((android.view.View) fileHelp.getParent())
                                    .getLayoutParams()).bottomMargin
                                    >= 8 * activity.getResources().getDisplayMetrics().density - 1,
                            "PNG mask heading keeps spacing before the file controls");
                    android.widget.SeekBar maskScale = decor.findViewWithTag("mask-scale");
                    check(maskScale != null && maskScale.isShown() && maskScale.getProgress() == 100,
                            "PNG mask tab exposes the mask scale slider");
                    decor.findViewWithTag("mask-scale-increase").performClick();
                    check(maskScale.getProgress() == 101, "Right arrow increases mask scale by exactly one");
                    decor.findViewWithTag("mask-scale-decrease").performClick();
                    decor.findViewWithTag("mask-scale-decrease").performClick();
                    check(maskScale.getProgress() == 99 && isolated.maskScale == 100,
                            "Left arrow decreases mask scale by one without saving before Apply");
                    maskScale.setProgress(50);
                    decor.findViewWithTag("mask-scale-decrease").performClick();
                    check(maskScale.getProgress() == 50, "Arrows stop at the minimum scale");
                    android.view.View help = decor.findViewWithTag("help-shape_preview_help");
                    check(help != null && help.getContentDescription().toString()
                                    .contains(activity.getString(R.string.choose_shape)),
                            "Shape popup header has a labelled help button");
                    help.performClick();
                    android.app.Dialog helpDialog = (android.app.Dialog) field(activity, "helpDialog");
                    android.widget.TextView helpText = findText(helpDialog.getWindow().getDecorView(),
                            activity.getString(R.string.shape_preview_help));
                    check(helpDialog.isShowing() && helpText != null && helpText.getTextSize()
                                    >= 16 * activity.getResources().getDisplayMetrics().scaledDensity - 1
                                    && findText(decor, activity.getString(R.string.shape_preview_help)) == null,
                            "Shape help opens in a readable popup instead of inline small text");
                    findText(helpDialog.getWindow().getDecorView(), activity.getString(R.string.close)).performClick();
                    check(!helpDialog.isShowing(), "Help popup closes");
                    fileTab = decor.findViewWithTag("shape-tab-presets");
                    fileTab.performClick();
                    for (IconShape shape : IconShape.values()) {
                        if (shape == IconShape.CUSTOM || shape == IconShape.FILE) continue;
                        android.view.View tile = decor.findViewWithTag(shape);
                        check(tile != null && tile.getContentDescription().equals(activity.getString(shape.label)),
                                "Each exported shape has a labelled illustration tile");
                        tile.performClick();
                        check(tile.isSelected() && tile.getStateDescription().equals(activity.getString(R.string.option_selected)),
                                "Shape tiles expose their selected state");
                    }
                    decor.findViewWithTag(IconShape.STAR).performClick();
                    check(!initial.sameAs(((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()),
                            "Choosing a shape immediately updates icons on the preview background");
                    check(isolated.shape == IconShape.ROUNDED_SQUARE
                                    && activity.getSharedPreferences("draft", 0).getString("json", "").equals(savedJson),
                            "Choosing preview shapes does not modify the draft before Apply");
                    dialog.dismiss();
                } finally { initial.recycle(); }
                Bitmap overlay = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setColor(0xB0FFFFFF);
                new android.graphics.Canvas(overlay).drawRect(24, 24, 168, 168, paint);
                paint.setColor(0xFF4080FF);
                new android.graphics.Canvas(overlay).drawRect(80, 80, 112, 112, paint);
                try { isolated.overlay = FileIcons.save(activity, overlay); }
                finally { overlay.recycle(); }
                ((android.view.View) field(activity, "shapeButton")).performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                android.view.View decor = dialog.getWindow().getDecorView();
                check(decor.findViewWithTag(IconShape.ROUNDED_SQUARE).isSelected(),
                        "Cancelling the popup restores the original selection");
                android.widget.SeekBar scale = decor.findViewWithTag("overlay-scale");
                check(scale != null && scale.getProgress() == 100,
                        "Selected overlay exposes its saved scale in the shape popup");
                android.widget.ImageView scene = decor.findViewWithTag("shape-scene");
                Bitmap before = ((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                scale.setProgress(119);
                scale.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                try { check(!before.sameAs(((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap())
                                && isolated.overlayScale == 100,
                        "Dragging overlay scale updates scene without changing draft before Apply"); }
                finally { before.recycle(); }
                dialog.dismiss();
                check(isolated.overlayScale == 100, "Cancelling shape popup discards preview overlay scale");
                ((android.view.View) field(activity, "shapeButton")).performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                android.view.View decor = dialog.getWindow().getDecorView();
                android.widget.SeekBar scale = decor.findViewWithTag("overlay-scale");
                check(scale.getProgress() == 100, "Reopened cancelled scale uses original value");
                scale.setProgress(119);
                scale.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                decor.findViewWithTag(IconShape.HEART).performClick();
                android.view.View apply = findText(decor, activity.getString(R.string.apply_settings));
                check(apply.isEnabled() && ((android.view.ViewGroup) apply.getParent()).getChildAt(1)
                                instanceof android.widget.ScrollView,
                        "Apply is enabled after loading and pinned outside the scrolling choices");
                apply.performClick();
                check(isolated.shape == IconShape.HEART && Draft.load(activity).shape == IconShape.HEART
                                && !dialog.isShowing(), "Apply saves the chosen mask and closes the popup");
                check(isolated.overlayScale == 120 && Draft.load(activity).overlayScale == 120,
                        "Apply persists overlay scale together with shape");
                ((android.view.View) field(activity, "shapeButton")).performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.widget.ImageView universal = (android.widget.ImageView) field(activity, "stylePreview");
                Bitmap current = ((android.graphics.drawable.BitmapDrawable) universal.getDrawable()).getBitmap();
                check(!universalBefore[0].sameAs(current),
                        "Applying shape and layer settings refreshes the pinned universal preview");
                universalBefore[0].recycle();
                universalBefore[0] = null;
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                check(dialog.getWindow().getDecorView().findViewWithTag(IconShape.HEART).isSelected(),
                        "Reopened popup selects the applied shape");
                android.view.View decor = dialog.getWindow().getDecorView();
                check(((android.widget.SeekBar) decor.findViewWithTag("overlay-scale"))
                                .getProgress() == 120, "Reopened popup restores applied overlay scale");
                android.widget.ImageView stepScene = decor.findViewWithTag("shape-scene");
                Bitmap beforeStep = ((android.graphics.drawable.BitmapDrawable) stepScene.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                decor.findViewWithTag("overlay-scale-increase").performClick();
                try {
                    check(((android.widget.SeekBar) decor.findViewWithTag("overlay-scale")).getProgress() == 121
                                    && !beforeStep.sameAs(((android.graphics.drawable.BitmapDrawable) stepScene.getDrawable()).getBitmap()),
                            "Overlay scale arrow steps by one and refreshes the preview");
                } finally { beforeStep.recycle(); }
                decor.findViewWithTag("overlay-scale-decrease").performClick();
                check(((android.widget.SeekBar) decor.findViewWithTag("overlay-scale")).getProgress() == 120,
                        "Overlay scale left arrow steps back by one");
                check(decor.findViewWithTag("help-overlay_scale_help") != null
                                && decor.findViewWithTag("help-overlay_clip_help") != null
                                && findText(decor, activity.getString(R.string.overlay_scale_help)) == null,
                        "Overlay scale and clip explanations moved to help buttons");
                android.widget.CheckBox clip = decor.findViewWithTag("overlay-clip");
                check(clip != null && clip.isChecked(), "Overlay clip toggle is shown and enabled by default");
                android.widget.ImageView scene = decor.findViewWithTag("shape-scene");
                Bitmap before = ((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                android.widget.SeekBar scaleBar = decor.findViewWithTag("overlay-scale");
                scaleBar.setProgress(149);
                scaleBar.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                Bitmap scaled = ((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                clip.setChecked(false);
                try {
                    check(!scaled.sameAs(((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()),
                            "Overlay clip toggle immediately updates the preview scene");
                } finally { before.recycle(); scaled.recycle(); }
                decor.findViewWithTag("shape-tab-editor").performClick();
                check(decor.findViewWithTag("shape-editor").getVisibility() == android.view.View.VISIBLE
                                && !decor.findViewWithTag(IconShape.HEART).isShown(),
                        "Editor tab replaces preset shapes with the corner editor");
                Bitmap editorStart = ((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                int[] wanted = {0, 100, 50, 10};
                for (int i = 0; i < 4; i++)
                    ((android.widget.SeekBar) decor.findViewWithTag("corner-" + i)).setProgress(wanted[i]);
                try {
                    check(!editorStart.sameAs(((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()),
                            "Corner sliders immediately update the preview scene");
                } finally { editorStart.recycle(); }
                check(isolated.shape == IconShape.HEART && isolated.overlayClip,
                        "Editor changes are not saved before Apply");
                findText(decor, activity.getString(R.string.apply_settings)).performClick();
                Draft saved = Draft.load(activity);
                check(isolated.shape == IconShape.CUSTOM && saved.shape == IconShape.CUSTOM
                                && java.util.Arrays.equals(saved.corners, wanted),
                        "Apply saves the custom corner shape");
                check(!saved.overlayClip && saved.overlayScale == 150,
                        "Apply persists overlay clip and 150% scale");
                ((android.view.View) field(activity, "shapeButton")).performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                android.view.View decor = dialog.getWindow().getDecorView();
                check(decor.findViewWithTag("shape-tab-editor").isSelected()
                                && decor.findViewWithTag("shape-editor").getVisibility() == android.view.View.VISIBLE
                                && ((android.widget.SeekBar) decor.findViewWithTag("corner-2")).getProgress() == 50
                                && !((android.widget.CheckBox) decor.findViewWithTag("overlay-clip")).isChecked(),
                        "Reopened popup restores editor tab, corners and clip toggle");
                ((android.widget.SeekBar) decor.findViewWithTag("corner-0")).setProgress(70);
                decor.findViewWithTag("shape-tab-presets").performClick();
                decor.findViewWithTag(IconShape.CIRCLE).performClick();
                dialog.dismiss();
                check(isolated.shape == IconShape.CUSTOM && isolated.corners[0] == 0,
                        "Cancelling discards editor and preset changes");
            });
        } finally {
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                if (dialog != null) dialog.dismiss();
                setField(activity, "draft", original);
                original.save(activity);
                build.invoke(activity);
            });
            if (universalBefore[0] != null) universalBefore[0].recycle();
        }
    }

    private void verifyIconScaleUi(MainActivity activity) throws Exception {
        Draft original = (Draft) field(activity, "draft");
        Draft isolated = Draft.fromJson(original.toJson());
        isolated.filters.clear();
        isolated.overlay = ""; isolated.overlayAll = false;
        isolated.background = ""; isolated.backgroundAll = false;
        isolated.iconScale = 100;
        var build = MainActivity.class.getDeclaredMethod("buildScreen");
        build.setAccessible(true);
        try {
            runOnMainChecked(() -> {
                setField(activity, "draft", isolated);
                build.invoke(activity);
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                check(decor.findViewWithTag("icon-scale") == null,
                        "Icon scale is no longer duplicated in theme options");
                check(decor.findViewWithTag("background-settings") == null
                                && decor.findViewWithTag("style-background-scale") != null,
                        "Background controls are inline rather than behind a settings button");
                IconRepository.Pack pack = (IconRepository.Pack) field(activity, "pack");
                int size = pack.iconSize();
                android.widget.TextView info = (android.widget.TextView) field(activity, "iconSizeInfo");
                check(info.getText().toString().contains(size + " × " + size + " px"),
                        "Theme options show the detected pack icon resolution: " + info.getText());
            });
        } finally {
            runOnMainChecked(() -> {
                setField(activity, "draft", original);
                original.save(activity);
                build.invoke(activity);
            });
        }
    }

    private static int opaquePixels(Bitmap bitmap) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 128) count++;
        }
        return count;
    }

    private void verifyOverlayUi(MainActivity activity) throws Exception {
        Draft original = (Draft) field(activity, "draft");
        Draft isolated = Draft.fromJson(original.toJson());
        isolated.filters.clear();
        isolated.overlay = ""; isolated.overlayAll = false;
        isolated.background = "";
        isolated.coverPath = ""; isolated.wallpaperPath = "";
        isolated.iconScale = 100;
        java.util.Set<String> beforeFiles = new java.util.HashSet<>();
        File directory = FileIcons.directory(activity);
        File[] existing = directory.listFiles();
        if (existing != null) for (File file : existing) beforeFiles.add(file.getName());
        try {
            runOnMainChecked(() -> {
                setField(activity, "draft", isolated);
                try {
                    var build = MainActivity.class.getDeclaredMethod("buildScreen");
                    build.setAccessible(true); build.invoke(activity);
                    android.view.View coverPanel = (android.view.View) field(field(activity, "coverSection"), "content");
                    android.view.View wallpaperPanel = (android.view.View) field(field(activity, "wallpaperSection"), "content");
                    android.view.View overlayPanel = (android.view.View) field(field(activity, "overlaySection"), "content");
                    android.view.ViewGroup form = (android.view.ViewGroup) coverPanel.getParent().getParent();
                    android.view.View coverCard = (android.view.View) coverPanel.getParent();
                    android.view.View wallpaperCard = (android.view.View) wallpaperPanel.getParent();
                    android.view.View overlayCard = (android.view.View) overlayPanel.getParent();
                    android.view.View backgroundCard = (android.view.View)
                            ((android.view.View) field(field(activity, "backgroundSection"), "content")).getParent();
                    check(form.indexOfChild(coverCard) + 1 == form.indexOfChild(wallpaperCard)
                                    && "style-section-0".equals(backgroundCard.getParent() instanceof android.view.ViewGroup backgroundGroup
                                    ? backgroundGroup.getTag() : null)
                                    && "style-section-1".equals(overlayCard.getParent() instanceof android.view.ViewGroup overlayGroup
                                    ? overlayGroup.getTag() : null),
                            "Cover and wallpaper stay together on Basics; layers occupy their Style tabs");
                    String unchanged = isolated.toJson().toString();
                    for (String section : List.of("backgroundSection", "overlaySection")) {
                        Object optional = field(activity, section);
                        check(((android.view.View) field(optional, "content")).getVisibility() == android.view.View.VISIBLE
                                        && ((android.view.View) field(optional, "toggle")).getParent() == null,
                                "Style layers are always expanded without a card heading or collapse button: " + section);
                    }
                    for (String section : List.of("coverSection", "wallpaperSection")) {
                        Object optional = field(activity, section);
                        android.view.View content = (android.view.View) field(optional, "content");
                        android.view.View toggle = (android.view.View) field(optional, "toggle");
                        check(((android.widget.LinearLayout.LayoutParams) content.getLayoutParams()).topMargin
                                        >= 8 * activity.getResources().getDisplayMetrics().density - 1,
                                "Optional section heading keeps spacing before its controls: " + section);
                        check(content.getVisibility() == android.view.View.GONE,
                                "Empty optional section starts collapsed: " + section);
                        toggle.performClick();
                        check(content.getVisibility() == android.view.View.VISIBLE
                                        && toggle.getStateDescription().equals(activity.getString(R.string.section_expanded)),
                                "Optional section expands with accessible state: " + section);
                        var refresh = MainActivity.class.getDeclaredMethod("refresh");
                        refresh.setAccessible(true); refresh.invoke(activity);
                        check(content.getVisibility() == android.view.View.VISIBLE,
                                "Refreshing does not collapse an opened empty section: " + section);
                        toggle.performClick();
                    }
                    check(isolated.toJson().toString().equals(unchanged),
                            "Expanding and collapsing sections does not change template data");
                    android.view.View header = ((android.view.ViewGroup) field(activity, "root")).getChildAt(0);
                    android.view.View settings = ((android.view.ViewGroup) header)
                            .getChildAt(((android.view.ViewGroup) header).getChildCount() - 1);
                    var settingsParams = (android.widget.LinearLayout.LayoutParams) settings.getLayoutParams();
                    check(settingsParams.getMarginStart() >= Math.round(12 * activity.getResources().getDisplayMetrics().density),
                            "Settings button has at least 12dp spacing from Templates");
                    check(((android.view.View) field(activity, "overlayScope")).getVisibility() == android.view.View.GONE,
                            "Overlay scope is hidden until an overlay is chosen");
                    Bitmap overlay = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
                    android.graphics.Paint paint = new android.graphics.Paint();
                    paint.setColor(0xFF16BA78);
                    new android.graphics.Canvas(overlay).drawRect(0, 0, 192, 12, paint);
                    var preview = MainActivity.class.getDeclaredMethod("previewOverlay", Bitmap.class);
                    preview.setAccessible(true); preview.invoke(activity, overlay);
                    android.app.Dialog dialog = (android.app.Dialog) field(activity, "overlayDialog");
                    check(dialog.isShowing(), "Overlay import shows a confirmation preview");
                    findText(dialog.getWindow().getDecorView(), activity.getString(R.string.use_overlay)).performClick();
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            waitForWork(activity);
            verifyPreviewOverlays(activity);
            runOnMainChecked(() -> {
                check(FileIcons.isFile(isolated.overlay), "Confirmed overlay is copied into local file storage");
                check(((android.view.View) field(field(activity, "overlaySection"), "content")).getVisibility()
                                == android.view.View.VISIBLE,
                        "Selecting an overlay expands its section");
                String imagePath = "file-icons/" + isolated.overlay.substring("fileicon:".length()) + ".png";
                isolated.coverPath = imagePath; isolated.wallpaperPath = imagePath;
                var build = MainActivity.class.getDeclaredMethod("buildScreen");
                build.setAccessible(true); build.invoke(activity);
                for (String section : List.of("coverSection", "wallpaperSection", "overlaySection")) {
                    check(((android.view.View) field(field(activity, section), "content")).getVisibility()
                                    == android.view.View.VISIBLE,
                            "Populated optional section reopens expanded: " + section);
                }
                isolated.coverPath = ""; isolated.wallpaperPath = "";
                android.widget.RadioGroup scope = (android.widget.RadioGroup) field(activity, "overlayScope");
                check(scope.getVisibility() == android.view.View.VISIBLE && !isolated.overlayAll,
                        "Overlay defaults to fallback-only scope");
                scope.getChildAt(1).performClick();
                check(isolated.overlayAll && Draft.load(activity).overlayAll, "All-icons scope is persisted in draft");
                ((android.view.View) field(activity, "removeOverlay")).performClick();
                check(isolated.overlay.isEmpty() && !isolated.overlayAll && scope.getVisibility() == android.view.View.GONE,
                        "Removing overlay clears its saved scope and controls");
                build.invoke(activity);
                for (String section : List.of("coverSection", "wallpaperSection")) {
                    check(((android.view.View) field(field(activity, section), "content")).getVisibility()
                                    == android.view.View.GONE,
                            "Cleared optional section reopens collapsed: " + section);
                }
            });
        } finally {
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "overlayDialog");
                if (dialog != null) dialog.dismiss();
                setField(activity, "draft", original);
                try {
                    original.save(activity);
                    var build = MainActivity.class.getDeclaredMethod("buildScreen");
                    build.setAccessible(true); build.invoke(activity);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) {
                if (!beforeFiles.contains(file.getName())) Files.deleteIfExists(file.toPath());
            }
        }
    }

    private void verifyBackgroundUi(MainActivity activity) throws Exception {
        Draft original = (Draft) field(activity, "draft");
        Draft isolated = Draft.fromJson(original.toJson());
        isolated.filters.clear();
        isolated.background = ""; isolated.backgroundScale = 100; isolated.backgroundClip = true; isolated.backgroundAll = false;
        isolated.overlay = ""; isolated.wallpaperPath = ""; isolated.shape = IconShape.ROUNDED_SQUARE;
        isolated.iconScale = 100;
        File directory = FileIcons.directory(activity);
        java.util.Set<String> beforeFiles = new java.util.HashSet<>();
        File[] existing = directory.listFiles();
        if (existing != null) for (File file : existing) beforeFiles.add(file.getName());
        var build = MainActivity.class.getDeclaredMethod("buildScreen");
        build.setAccessible(true);
        try {
            runOnMainChecked(() -> {
                setField(activity, "draft", isolated);
                build.invoke(activity);
                check(((android.view.View) field(field(activity, "backgroundSection"), "content")).getVisibility()
                                == android.view.View.VISIBLE, "Empty background controls remain visible");
                check(((android.view.View) field(activity, "backgroundScope")).getVisibility() == android.view.View.GONE,
                        "Background scope is hidden before import");
                isolated.save(activity);
                Bitmap image = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
                image.eraseColor(android.graphics.Color.GREEN);
                var preview = MainActivity.class.getDeclaredMethod("previewBackground", Bitmap.class);
                preview.setAccessible(true); preview.invoke(activity, image);
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "backgroundDialog");
                check(dialog.isShowing(), "Background import shows a confirmation preview");
                android.view.View decor = dialog.getWindow().getDecorView();
                check(decor.findViewWithTag("background-scale") != null && decor.findViewWithTag("background-clip") != null
                                && decor.findViewWithTag("background-import-scope") != null
                                && decor.findViewWithTag("icon-scale") != null,
                        "Background import offers background scale, icon scale, clipping and scope");
            });
            waitForThumbnails(activity);
            Bitmap[] importedBefore = new Bitmap[2];
            runOnMainChecked(() -> {
                android.view.View decor = ((android.app.Dialog) field(activity, "backgroundDialog")).getWindow().getDecorView();
                for (String type : List.of("system", "pack")) for (int i = 0; i < 3; i++) {
                    android.widget.ImageView image = decor.findViewWithTag("background-example-" + type + "-" + i);
                    check(image != null && image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable,
                            "Background import previews " + type + " icon " + i);
                }
                android.widget.ImageView system = decor.findViewWithTag("background-example-system-0");
                Bitmap shown = ((android.graphics.drawable.BitmapDrawable) system.getDrawable()).getBitmap();
                importedBefore[0] = shown.copy(Bitmap.Config.ARGB_8888, false);
                android.widget.SeekBar scale = decor.findViewWithTag("background-scale");
                scale.setProgress(60);
                scale.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT));
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "backgroundDialog");
                android.view.View decor = dialog.getWindow().getDecorView();
                android.widget.ImageView system = decor.findViewWithTag("background-example-system-0");
                Bitmap shown = ((android.graphics.drawable.BitmapDrawable) system.getDrawable()).getBitmap();
                check(!shown.sameAs(importedBefore[0]), "Background import scale updates the examples");
                importedBefore[0].recycle();
                importedBefore[0] = shown.copy(Bitmap.Config.ARGB_8888, false);
                android.widget.ImageView packed = decor.findViewWithTag("background-example-pack-0");
                importedBefore[1] = ((android.graphics.drawable.BitmapDrawable) packed.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                var previous = packed.getDrawable();
                for (int i = 0; i < 40; i++) {
                    decor.findViewWithTag("icon-scale-decrease").performClick();
                    check(packed.getDrawable() == previous, "Icon scale keeps the rendered icon while waiting: " + i);
                }
                check(isolated.iconScale == 100 && Draft.load(activity).iconScale == 100,
                        "Icon scale stays local until background confirmation");
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "backgroundDialog");
                android.view.View decor = dialog.getWindow().getDecorView();
                android.widget.ImageView system = decor.findViewWithTag("background-example-system-0");
                android.widget.ImageView packed = decor.findViewWithTag("background-example-pack-0");
                check(((android.graphics.drawable.BitmapDrawable) system.getDrawable()).getBitmap().sameAs(importedBefore[0]),
                        "Pack icon scale does not resize system fallback icons");
                check(opaquePixels(((android.graphics.drawable.BitmapDrawable) packed.getDrawable()).getBitmap())
                                < opaquePixels(importedBefore[1]) * .6,
                        "Background dialog icon scale shrinks pack artwork");
                for (Bitmap image : importedBefore) image.recycle();
                ((android.widget.SeekBar) decor.findViewWithTag("background-scale")).setProgress(100);
                ((android.widget.SeekBar) decor.findViewWithTag("background-scale")).onKeyDown(
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT, new android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                ((android.widget.SeekBar) decor.findViewWithTag("background-scale")).onKeyDown(
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT, new android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT));
                findText(decor, activity.getString(R.string.use_background)).performClick();
            });
            waitForWork(activity);
            runOnMainChecked(() -> {
                check(FileIcons.isFile(isolated.background) && !isolated.backgroundAll && isolated.backgroundScale == 100,
                        "Confirmed background is saved with fallback-only scope and chosen scale");
                check(isolated.iconScale == 60 && Draft.load(activity).iconScale == 60,
                        "Confirming the background saves icon scale");
                check(((android.view.View) field(field(activity, "backgroundSection"), "content")).getVisibility()
                                == android.view.View.VISIBLE, "Selecting a background expands its card");
                check(((android.widget.SeekBar) activity.getWindow().getDecorView()
                                .findViewWithTag("style-background-scale")).getProgress() == 100,
                        "Inline background controls reflect imported settings");
            });
            waitForWork(activity);
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                check(((android.widget.SeekBar) decor.findViewWithTag("style-icon-scale")).getProgress() == 60
                                && ((android.widget.SeekBar) decor.findViewWithTag("style-background-scale")).getProgress() == 100,
                        "Inline settings reflect saved scales");
                decor.findViewWithTag("style-icon-scale-increase").performClick();
                decor.findViewWithTag("style-background-scale-decrease").performClick();
                check(isolated.iconScale == 61 && isolated.backgroundScale == 99,
                        "Inline settings apply immediately");
                decor.findViewWithTag("style-icon-scale-decrease").performClick();
                decor.findViewWithTag("style-background-scale-increase").performClick();
                ((android.view.View) field(activity, "shapeButton")).performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                android.view.View decor = dialog.getWindow().getDecorView();
                android.widget.SeekBar scale = decor.findViewWithTag("background-scale");
                android.widget.CheckBox clip = decor.findViewWithTag("background-clip");
                check(scale != null && scale.getProgress() == 100 && clip.isChecked()
                                && decor.findViewWithTag("overlay-scale") == null,
                        "Shape picker shows only the selected background controls");
                android.widget.ImageView scene = decor.findViewWithTag("shape-scene");
                Bitmap before = ((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                scale.setProgress(149);
                scale.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                clip.setChecked(false);
                decor.findViewWithTag(IconShape.HEART).performClick();
                try { check(!before.sameAs(((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()),
                        "Background scale and clipping update the six-icon wallpaper preview"); }
                finally { before.recycle(); }
                check(isolated.backgroundScale == 100 && isolated.backgroundClip,
                        "Background preview changes do not modify the draft before Apply");
                findText(decor, activity.getString(R.string.apply_settings)).performClick();
                Draft saved = Draft.load(activity);
                check(saved.backgroundScale == 150 && !saved.backgroundClip && saved.shape == IconShape.HEART,
                        "Apply persists background scale, clipping and shape together");
                ((android.view.View) field(activity, "shapeButton")).performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                android.view.View decor = dialog.getWindow().getDecorView();
                android.widget.SeekBar scale = decor.findViewWithTag("background-scale");
                check(scale.getProgress() == 150 && !((android.widget.CheckBox) decor.findViewWithTag("background-clip")).isChecked(),
                        "Reopened picker restores background controls");
                scale.setProgress(79);
                scale.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                ((android.widget.CheckBox) decor.findViewWithTag("background-clip")).setChecked(true);
                dialog.dismiss();
                check(isolated.backgroundScale == 150 && !isolated.backgroundClip,
                        "Cancelling the shape picker discards background changes");
                ((android.widget.RadioGroup) field(activity, "backgroundScope")).getChildAt(1).performClick();
                check(isolated.backgroundAll && Draft.load(activity).backgroundAll, "All-icons background scope is saved");
            });
            verifyBackgroundPreviews(activity);
            runOnMainChecked(() -> {
                Bitmap overlay = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setColor(android.graphics.Color.BLUE);
                new android.graphics.Canvas(overlay).drawRect(0, 0, 192, 20, paint);
                try { isolated.overlay = FileIcons.save(activity, overlay); }
                finally { overlay.recycle(); }
                isolated.overlayScale = 100; isolated.overlayClip = false;
                build.invoke(activity);
                ((android.view.View) field(activity, "shapeButton")).performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                android.view.View decor = dialog.getWindow().getDecorView();
                android.widget.SeekBar background = decor.findViewWithTag("background-scale");
                android.widget.SeekBar overlay = decor.findViewWithTag("overlay-scale");
                check(background != null && overlay != null && decor.findViewWithTag("background-clip") != null
                                && decor.findViewWithTag("overlay-clip") != null,
                        "Background and overlay controls coexist in the shape picker");
                android.widget.ImageView scene = decor.findViewWithTag("shape-scene");
                Bitmap before = ((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                background.setProgress(51);
                background.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT));
                try { check(!before.sameAs(((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()),
                        "Changing background scale updates the scene independently of overlay"); }
                finally { before.recycle(); }
                before = ((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                overlay.setProgress(149);
                overlay.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                try { check(!before.sameAs(((android.graphics.drawable.BitmapDrawable) scene.getDrawable()).getBitmap()),
                        "Changing overlay scale updates the scene independently of background"); }
                finally { before.recycle(); }
                dialog.dismiss();
                check(isolated.backgroundScale == 150 && isolated.overlayScale == 100,
                        "Cancelling discards both independent layer scales");
                ((android.view.View) field(activity, "removeOverlay")).performClick();
                build.invoke(activity);
                check(((android.view.View) field(field(activity, "backgroundSection"), "content")).getVisibility()
                                == android.view.View.VISIBLE, "Populated background card reopens expanded");
                ((android.view.View) field(activity, "removeBackground")).performClick();
                check(isolated.background.isEmpty() && !isolated.backgroundAll && isolated.backgroundClip
                                && isolated.backgroundScale == 100 && !isolated.toJson().has("background"),
                        "Removing a background clears its reference and resets related settings");
                check(((android.widget.SeekBar) activity.getWindow().getDecorView()
                                .findViewWithTag("style-background-scale")).getProgress() == 100,
                        "Removing a background resets the inline scale");
                ((android.view.View) field(activity, "shapeButton")).performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "shapeDialog");
                check(dialog.getWindow().getDecorView().findViewWithTag("background-scale") == null
                                && dialog.getWindow().getDecorView().findViewWithTag("background-clip") == null,
                        "Removing the background also hides its shape controls");
                dialog.dismiss();
            });
        } finally {
            runOnMainChecked(() -> {
                for (String name : List.of("backgroundDialog", "shapeDialog", "iconDialog", "fileIconDialog")) {
                    android.app.Dialog dialog = (android.app.Dialog) field(activity, name);
                    if (dialog != null) dialog.dismiss();
                }
                setField(activity, "draft", original);
                original.save(activity); build.invoke(activity);
            });
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) if (!beforeFiles.contains(file.getName())) Files.deleteIfExists(file.toPath());
        }
    }

    private void verifyBackgroundPreviews(MainActivity activity) throws Exception {
        Draft draft = (Draft) field(activity, "draft");
        String reference = draft.background;
        IconRepository.Pack pack = (IconRepository.Pack) field(activity, "pack");
        IconRepository.Pack source = pack.members.isEmpty() ? pack : pack.members.get(0);
        String iconName = source.icons.keySet().iterator().next();
        @SuppressWarnings("unchecked")
        IconRepository.App app = ((List<IconRepository.App>) field(activity, "apps")).get(0);
        Bitmap file = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.RED);
        new android.graphics.Canvas(file).drawRect(64, 64, 128, 128, paint);
        String fileReference = FileIcons.save(activity, file);
        var bind = MainActivity.class.getDeclaredMethod("bindImage",
                android.widget.ImageView.class, IconRepository.Pack.class, String.class);
        var bindSystem = MainActivity.class.getDeclaredMethod("bindSystemImage",
                android.widget.ImageView.class, IconRepository.App.class);
        bind.setAccessible(true); bindSystem.setAccessible(true);
        try {
            for (String mode : List.of("fallback", "all", "scaled", "clipped", "removed", "all")) {
                android.widget.ImageView[] views = new android.widget.ImageView[3];
                runOnMainChecked(() -> {
                    draft.background = mode.equals("removed") ? "" : reference;
                    draft.backgroundAll = !mode.equals("fallback");
                    draft.backgroundScale = mode.equals("scaled") ? 50 : 150;
                    draft.backgroundClip = mode.equals("clipped");
                    for (int i = 0; i < views.length; i++) views[i] = new android.widget.ImageView(activity);
                    bind.invoke(activity, views[0], source, iconName);
                    bind.invoke(activity, views[1], pack, fileReference);
                    bindSystem.invoke(activity, views[2], app);
                });
                waitForThumbnails(activity);
                Bitmap mask = draftMask(activity, draft);
                Bitmap[] expected = {IconRepository.Pack.scaleArtwork(source.render(iconName, 128), draft.iconScale),
                        IconRepository.Pack.scaleArtwork(pack.render(fileReference, 128), draft.iconScale),
                        draft.shape.preview(IconRepository.unthemedIcon(activity.getPackageManager(), app.component()),
                                pack.fallbackSize(), draft.corners, mask)};
                Bitmap background = IconBackground.load(activity, draft.background, draft.backgroundScale);
                Bitmap backdrop = background == null ? null : IconBackground.render(background, draft.shape,
                        draft.corners, pack.fallbackSize(), draft.backgroundClip, mask);
                if (mask != null) mask.recycle();
                try {
                    for (int i = 0; i < expected.length; i++) if (i == 2 || draft.backgroundAll) IconBackground.draw(expected[i], backdrop);
                    runOnMainChecked(() -> {
                        for (int i = 0; i < views.length; i++) check(
                                ((android.graphics.drawable.BitmapDrawable) views[i].getDrawable()).getBitmap().sameAs(expected[i]),
                                "Background preview scope, scale, shape and cache: " + mode + "/" + i);
                    });
                } finally {
                    for (Bitmap bitmap : expected) bitmap.recycle();
                    if (background != null) background.recycle();
                    if (backdrop != null) backdrop.recycle();
                }
            }
            MainActivity.IconChoice[] choice = new MainActivity.IconChoice[1];
            android.widget.ImageView[] pickerImage = new android.widget.ImageView[1];
            runOnMainChecked(() -> {
                activity.showIcons(app, () -> {});
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "iconDialog");
                android.widget.GridView grid = findGrid(dialog.getWindow().getDecorView());
                choice[0] = (MainActivity.IconChoice) grid.getAdapter().getItem(0);
                android.view.ViewGroup tile = (android.view.ViewGroup) grid.getAdapter().getView(0, null, grid);
                pickerImage[0] = (android.widget.ImageView) ((android.view.ViewGroup) tile.getChildAt(0)).getChildAt(0);
            });
            waitForThumbnails(activity);
            Bitmap background = IconBackground.load(activity, draft.background, draft.backgroundScale);
            Bitmap pickerMask = draftMask(activity, draft);
            Bitmap backdrop = IconBackground.render(background, draft.shape, draft.corners, pack.fallbackSize(),
                    draft.backgroundClip, pickerMask);
            if (pickerMask != null) pickerMask.recycle();
            try {
                runOnMainChecked(() -> {
                    Bitmap expected = IconRepository.Pack.scaleArtwork(
                            choice[0].source().render(choice[0].icon().name(), 128), draft.iconScale);
                    IconBackground.draw(expected, backdrop);
                    try { check(((android.graphics.drawable.BitmapDrawable) pickerImage[0].getDrawable()).getBitmap().sameAs(expected),
                            "Manual icon catalogue displays the selected background"); }
                    finally { expected.recycle(); }
                    ((android.app.Dialog) field(activity, "iconDialog")).dismiss();
                    var preview = MainActivity.class.getDeclaredMethod("previewFileIcon", String.class, Runnable.class, Bitmap.class);
                    preview.setAccessible(true);
                    preview.invoke(activity, app.key(), (Runnable) () -> {}, file.copy(Bitmap.Config.ARGB_8888, true));
                });
                waitForThumbnails(activity);
                runOnMainChecked(() -> {
                    android.app.Dialog dialog = (android.app.Dialog) field(activity, "fileIconDialog");
                    android.view.ViewGroup content = (android.view.ViewGroup) dialog.findViewById(android.R.id.content);
                    android.widget.ImageView image = (android.widget.ImageView) ((android.view.ViewGroup) content.getChildAt(0)).getChildAt(1);
                    Bitmap expected = IconRepository.Pack.scaleArtwork(file.copy(Bitmap.Config.ARGB_8888, true), draft.iconScale);
                    IconBackground.draw(expected, backdrop);
                    try { check(((android.graphics.drawable.BitmapDrawable) image.getDrawable()).getBitmap().sameAs(expected),
                            "File import preview displays the background under the original icon"); }
                    finally { expected.recycle(); }
                    findText(dialog.getWindow().getDecorView(), activity.getString(R.string.use_icon_file)).performClick();
                });
                waitForWork(activity);
                Bitmap saved = pack.render(draft.overrides.get(app.key()), 192);
                try { check(saved.sameAs(file), "File import stores the original icon without baking in its background"); }
                finally { saved.recycle(); }
            } finally { background.recycle(); backdrop.recycle(); }
        } finally { file.recycle(); }
    }

    /** UI checks run against the user's draft, which may use an imported PNG mask. */
    private static Bitmap draftMask(Context context, Draft draft) throws java.io.IOException {
        return IconMask.load(context, draft.shape == IconShape.FILE ? draft.shapeFile : "", draft.maskScale);
    }

    private void verifyPreviewOverlays(MainActivity activity) throws Exception {
        Draft draft = (Draft) field(activity, "draft");
        IconRepository.Pack pack = (IconRepository.Pack) field(activity, "pack");
        @SuppressWarnings("unchecked")
        List<IconRepository.App> apps = (List<IconRepository.App>) field(activity, "apps");
        IconRepository.App app = apps.get(0);
        IconRepository.Pack source = pack.members.isEmpty() ? pack : pack.members.get(0);
        String icon = source.icons.keySet().iterator().next();
        String previousOverlay = draft.overlay;
        boolean previousAll = draft.overlayAll;
        int previousScale = draft.overlayScale;
        boolean previousClip = draft.overlayClip;
        Map<String, String> previousOverrides = new LinkedHashMap<>(draft.overrides);
        Bitmap file = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        file.eraseColor(android.graphics.Color.BLUE);
        String fileReference = FileIcons.save(activity, file);
        Bitmap otherOverlay = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.RED);
        new android.graphics.Canvas(otherOverlay).drawRect(0, 0, 192, 16, paint);
        String otherReference = FileIcons.save(activity, otherOverlay);
        otherOverlay.recycle();
        var bind = MainActivity.class.getDeclaredMethod("bindImage",
                android.widget.ImageView.class, IconRepository.Pack.class, String.class);
        var bindSystem = MainActivity.class.getDeclaredMethod("bindSystemImage",
                android.widget.ImageView.class, IconRepository.App.class);
        bind.setAccessible(true); bindSystem.setAccessible(true);
        try {
            for (String mode : List.of("fallback", "all", "scaled", "unclipped", "removed", "changed", "fallback", "all")) {
                boolean all = mode.equals("all") || mode.equals("changed") || mode.equals("scaled") || mode.equals("unclipped");
                boolean clip = !mode.equals("unclipped");
                int scale = mode.equals("scaled") ? 120 : mode.equals("unclipped") ? 150 : 100;
                String overlayReference = mode.equals("removed") ? ""
                        : mode.equals("changed") ? otherReference : previousOverlay;
                android.widget.ImageView[] views = new android.widget.ImageView[4];
                Bitmap[] placeholder = new Bitmap[1];
                runOnMainChecked(() -> {
                    draft.overlayAll = all; draft.overlay = overlayReference;
                    draft.overlayScale = scale; draft.overlayClip = clip;
                    for (int i = 0; i < views.length; i++) views[i] = new android.widget.ImageView(activity);
                    bind.invoke(activity, views[0], source, icon);
                    bind.invoke(activity, views[1], pack, fileReference);
                    bindSystem.invoke(activity, views[2], app);
                    bind.invoke(activity, views[3], pack, null);
                    placeholder[0] = IconShape.previewSource(views[3].getDrawable(), false);
                });
                waitForThumbnails(activity);
                Bitmap overlay = IconOverlay.load(activity, overlayReference, scale);
                IconRepository.Pack activePack = (IconRepository.Pack) field(activity, "pack");
                Bitmap mask = draftMask(activity, draft);
                Bitmap[] expected = {source.render(icon, 128), pack.render(fileReference, 128),
                        draft.shape.preview(IconRepository.unthemedIcon(activity.getPackageManager(), app.component()),
                                activePack.fallbackSize(), draft.corners, mask)};
                try {
                    for (int i = 0; i < 2; i++) if (all) IconOverlay.draw(expected[i], overlay, clip);
                    if (overlay != null) {
                        Bitmap border = IconOverlay.border(overlay, draft.shape, draft.corners, activePack.fallbackSize(), clip, mask);
                        try { IconOverlay.draw(expected[2], border); } finally { border.recycle(); }
                    }
                    runOnMainChecked(() -> {
                        for (int i = 0; i < expected.length; i++) {
                            Bitmap actual = ((android.graphics.drawable.BitmapDrawable) views[i].getDrawable()).getBitmap();
                            check(actual.sameAs(expected[i]), "Preview pixels respect shape, overlay scope and cache: " + mode + "/" + i);
                        }
                        Bitmap hidden = IconShape.previewSource(views[3].getDrawable(), false);
                        try { check(hidden.sameAs(placeholder[0]), "Hidden system icons keep their placeholder without an overlay"); }
                        finally { hidden.recycle(); }
                    });
                } finally {
                    if (overlay != null) overlay.recycle();
                    if (mask != null) mask.recycle();
                    for (Bitmap bitmap : expected) bitmap.recycle();
                    placeholder[0].recycle();
                }
            }
            IconShape previousShape = draft.shape;
            String loopOverlay = draft.overlay;
            try {
                Bitmap[] byShape = new Bitmap[2];
                IconShape[] compared = {IconShape.HEART, IconShape.SQUARE};
                for (int s = 0; s < compared.length; s++) {
                    int index = s;
                    android.widget.ImageView[] view = new android.widget.ImageView[1];
                    runOnMainChecked(() -> {
                        draft.shape = compared[index]; draft.overlay = "";
                        view[0] = new android.widget.ImageView(activity);
                        bindSystem.invoke(activity, view[0], app);
                    });
                    waitForThumbnails(activity);
                    runOnMainChecked(() -> byShape[index] = ((android.graphics.drawable.BitmapDrawable) view[0].getDrawable()).getBitmap());
                }
                check(!byShape[0].sameAs(byShape[1]), "System icons in the app list follow the selected template shape");
                int corner = (IconShape.SIZE - ((IconRepository.Pack) field(activity, "pack")).fallbackSize()) / 2 + 2;
                check(android.graphics.Color.alpha(byShape[1].getPixel(IconShape.SIZE / 2, corner)) == 255,
                        "Square system icon is not clipped by the currently applied HONOR mask");
            } finally { runOnMainChecked(() -> { draft.shape = previousShape; draft.overlay = loopOverlay; }); }
            android.widget.ImageView[] pickerImage = new android.widget.ImageView[1];
            MainActivity.IconChoice[] choice = new MainActivity.IconChoice[1];
            runOnMainChecked(() -> {
                activity.showIcons(app, () -> {});
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "iconDialog");
                android.widget.GridView grid = findGrid(dialog.getWindow().getDecorView());
                choice[0] = (MainActivity.IconChoice) grid.getAdapter().getItem(0);
                android.view.ViewGroup tile = (android.view.ViewGroup) grid.getAdapter().getView(0, null, grid);
                pickerImage[0] = (android.widget.ImageView) ((android.view.ViewGroup) tile.getChildAt(0)).getChildAt(0);
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                Bitmap expected = withOverlay(activity, choice[0].source().render(choice[0].icon().name(), 128), previousOverlay);
                try { check(((android.graphics.drawable.BitmapDrawable) pickerImage[0].getDrawable()).getBitmap().sameAs(expected),
                        "Manual icon catalogue applies the all-icons overlay"); }
                finally { expected.recycle(); }
                ((android.app.Dialog) field(activity, "iconDialog")).dismiss();
                var preview = MainActivity.class.getDeclaredMethod("previewFileIcon", String.class, Runnable.class, Bitmap.class);
                preview.setAccessible(true);
                preview.invoke(activity, app.key(), (Runnable) () -> {}, file.copy(Bitmap.Config.ARGB_8888, true));
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "fileIconDialog");
                android.view.ViewGroup content = (android.view.ViewGroup) dialog.findViewById(android.R.id.content);
                android.widget.ImageView image = (android.widget.ImageView) ((android.view.ViewGroup) content.getChildAt(0)).getChildAt(1);
                Bitmap expected = withOverlay(activity, file.copy(Bitmap.Config.ARGB_8888, true), previousOverlay);
                try { check(((android.graphics.drawable.BitmapDrawable) image.getDrawable()).getBitmap().sameAs(expected),
                        "File import preview applies the all-icons overlay"); }
                finally { expected.recycle(); }
                findText(dialog.getWindow().getDecorView(), activity.getString(R.string.use_icon_file)).performClick();
            });
            waitForWork(activity);
            Bitmap saved = pack.render(draft.overrides.get(app.key()), 192);
            try { check(saved.sameAs(file), "Preview overlay is not baked into the saved source file"); }
            finally { saved.recycle(); }
        } finally {
            runOnMainChecked(() -> {
                for (String name : List.of("iconDialog", "fileIconDialog")) {
                    android.app.Dialog dialog = (android.app.Dialog) field(activity, name);
                    if (dialog != null) dialog.dismiss();
                }
                draft.overlay = previousOverlay; draft.overlayAll = previousAll;
                draft.overlayScale = previousScale;
                draft.overlayClip = previousClip;
                draft.overrides.clear(); draft.overrides.putAll(previousOverrides);
            });
            file.recycle();
        }
    }

    private Bitmap withOverlay(Context context, Bitmap image, String reference) throws Exception {
        Bitmap overlay = IconOverlay.load(context, reference);
        try { IconOverlay.draw(image, overlay, true); return image; }
        finally { if (overlay != null) overlay.recycle(); }
    }

    private void waitForThumbnails(MainActivity activity) throws Exception {
        var executor = (java.util.concurrent.ThreadPoolExecutor) field(activity, "thumbnails");
        long deadline = android.os.SystemClock.uptimeMillis() + 35000;
        do {
            waitForIdleSync();
            var ready = new java.util.concurrent.CountDownLatch(2);
            List<java.util.concurrent.Future<?>> barriers = new ArrayList<>();
            for (int i = 0; i < 2; i++) barriers.add(executor.submit(() -> {
                ready.countDown();
                if (!ready.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Thumbnail queue timed out");
                return null;
            }));
            for (var barrier : barriers) barrier.get(35, java.util.concurrent.TimeUnit.SECONDS);
            waitForIdleSync();
            if (executor.getActiveCount() == 0 && executor.getQueue().isEmpty()) return;
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Thumbnail updates did not settle");
    }

    private void verifySettingsResources(Context context) {
        check(App.initialLanguage(android.os.LocaleList.forLanguageTags("ru-RU,en-US")).equals("ru")
                        && App.initialLanguage(android.os.LocaleList.forLanguageTags("en-GB")).equals("en")
                        && App.initialLanguage(android.os.LocaleList.forLanguageTags("de-DE,ru-RU")).equals("ru")
                        && App.initialLanguage(android.os.LocaleList.forLanguageTags("de-DE")).equals("en"),
                "First-run language follows supported system language and falls back to English");
        for (String code : List.of("en", "ru")) {
            android.content.res.Configuration config = new android.content.res.Configuration(
                    context.getResources().getConfiguration());
            config.setLocales(android.os.LocaleList.forLanguageTags(code));
            Context localized = context.createConfigurationContext(config);
            check(localized.getString(R.string.settings).equals(code.equals("en") ? "Settings" : "Настройки"),
                    "Settings have English and Russian translations");
            check(!localized.getString(R.string.overlay_help).isBlank()
                            && localized.getString(R.string.pack_limit, 10).contains("10"),
                    "Overlay help and formatted translations resolve");
        }
        var preferences = getContext().getSharedPreferences("settings-check", 0);
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return preferences;
            }
        };
        try {
            preferences.edit().clear().commit();
            check(App.themed(isolated) == isolated, "Appearance defaults to follow system");
            for (String mode : List.of("dark", "light")) {
                preferences.edit().putString("theme", mode).commit();
                Context themed = App.themed(isolated);
                boolean night = (themed.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                check(night == mode.equals("dark"), "Explicit appearance overrides system night mode");
                android.view.ContextThemeWrapper wrapper = new android.view.ContextThemeWrapper(themed, R.style.AppTheme);
                android.util.TypedValue value = new android.util.TypedValue();
                wrapper.getTheme().resolveAttribute(android.R.attr.colorBackground, value, true);
                check(value.data == (night ? android.graphics.Color.BLACK : android.graphics.Color.rgb(245, 245, 245)),
                        "Light and dark resource palettes match requested appearance");
            }
        } finally { preferences.edit().clear().commit(); }
    }

    private void verifyExtraLauncherEntries(Context context, IconRepository repository) throws Exception {
        List<IconRepository.App> apps = repository.apps();
        android.content.pm.PackageManager manager = context.getPackageManager();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (IconRepository.App app : apps) check(keys.add(app.key()), "Launcher entries are unique: " + app.key());
        var launcherApps = context.getSystemService(android.content.pm.LauncherApps.class);
        java.util.Set<String> main = new java.util.HashSet<>();
        for (var info : manager.queryIntentActivities(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)) main.add(info.activityInfo.packageName);
        int profileOnly = 0;
        for (android.os.UserHandle user : launcherApps.getProfiles()) {
            if (user.equals(android.os.Process.myUserHandle())) continue;
            for (var info : launcherApps.getActivityList(null, user)) {
                if (main.contains(info.getComponentName().getPackageName())) continue;
                profileOnly++;
                String key = info.getComponentName().flattenToString();
                check(keys.contains(key), "Profile-only app is listed for theming: " + key);
                Bitmap icon = IconShape.previewSource(IconRepository.unthemedIcon(manager, info.getComponentName()), true);
                try { check(IconRepository.Pack.visibleBounds(icon).width() > 0, "Profile-only app icon renders: " + key); }
                finally { icon.recycle(); }
            }
        }
        boolean parallel;
        try { manager.getApplicationInfo(IconRepository.PARALLEL_SPACE, 0); parallel = true; }
        catch (android.content.pm.PackageManager.NameNotFoundException absent) { parallel = false; }
        IconRepository.App entry = apps.stream().filter(app -> app.component().getPackageName()
                .equals("com.hihonor.parallelspace")).findFirst().orElse(null);
        check(parallel == (entry != null), "Parallel Space desktop entry uses its actual package");
        check(apps.stream().noneMatch(app -> app.component().getPackageName().equals("com.hihonor.pallarspace")),
                "Parallel Space is not listed under the misspelled package");
        String legacyKey = "com.hihonor.pallarspace/com.hihonor.parallelspace.launcher.InitActivity";
        String correctedKey = "com.hihonor.parallelspace/com.hihonor.parallelspace.launcher.InitActivity";
        for (String value : new String[]{"", "dev.test:parallel"}) {
            org.json.JSONObject saved = new org.json.JSONObject().put("overrides",
                    new org.json.JSONObject().put(legacyKey, value));
            Draft restored = Draft.fromJson(saved);
            check(value.equals(restored.overrides.get(correctedKey)) && !restored.overrides.containsKey(legacyKey),
                    "Saved Parallel Space assignments migrate to the corrected package");
            saved.getJSONObject("overrides").put(correctedKey, "dev.test:current");
            check("dev.test:current".equals(Draft.fromJson(saved).overrides.get(correctedKey)),
                    "Current Parallel Space assignment takes priority over a legacy assignment");
        }
        if (entry != null) {
            Bitmap icon = IconShape.previewSource(IconRepository.unthemedIcon(manager, entry.component()), true);
            try { check(IconRepository.Pack.visibleBounds(icon).width() > 0, "Parallel Space icon renders"); }
            finally { icon.recycle(); }
            IconRepository.Pack fake = new IconRepository.Pack("dev.test", "Test", context.getResources(), null, manager);
            fake.icons.put("parallel", new IconRepository.Icon("parallel", "", R.drawable.app_icon));
            fake.byPackage.put(IconRepository.PARALLEL_SPACE, "parallel");
            check("parallel".equals(fake.automatic(entry)), "Pack icon for Parallel Space package themes its desktop entry");
            check(ThemeEngine.targets(List.of(entry), fake, Map.of()).containsKey("com.hihonor.parallelspace"),
                    "Parallel Space export targets the actual package");
            Bitmap cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            IconFilters.ColorCorrection correction = new IconFilters.ColorCorrection();
            correction.set(2, -100);
            try {
                for (boolean fullPack : new boolean[]{false, true}) {
                    for (boolean filtered : new boolean[]{false, true}) {
                        ThemeEngine.Built built = ThemeEngine.build(context, "Parallel Space", fullPack,
                                IconShape.NONE, null, false, fake, List.of(entry),
                                filtered ? Map.of(entry.key(), "") : Map.of(), cover, null, null, false,
                                false, null, false, true, null, 100,
                                filtered ? List.of(correction) : List.of(), (done, total) -> {});
                        try (ZipFile archive = new ZipFile(built.file());
                             ZipInputStream icons = new ZipInputStream(archive.getInputStream(archive.getEntry("icons")))) {
                            java.util.Set<String> names = new java.util.HashSet<>();
                            for (ZipEntry file; (file = icons.getNextEntry()) != null;) names.add(file.getName());
                            check(names.contains("com.hihonor.parallelspace.png")
                                            && !names.contains("com.hihonor.pallarspace.png"),
                                    "Parallel Space PNG uses the actual package: full=" + fullPack + ", filtered=" + filtered);
                        } finally { Files.deleteIfExists(built.file().toPath()); }
                    }
                }
            } finally { cover.recycle(); }
        }
        Bundle report = new Bundle();
        report.putString("stream", "Extra launcher entries: profile-only=" + profileOnly + ", parallelSpace=" + parallel + "\n");
        sendStatus(0, report);
    }

    private void verifyNativeResolution(Context context, IconRepository repository,
                                        List<IconRepository.PackInfo> packs, IconRepository.Pack base) throws Exception {
        long start = android.os.SystemClock.elapsedRealtime();
        int baseSize = base.iconSize();
        check(baseSize > 0 && base.iconSize() == baseSize, "Native pack resolution is valid and cached");
        IconRepository.Pack high = packs.stream().anyMatch(info -> info.packageName().equals("com.pressedprem.iconpack"))
                ? repository.load("com.pressedprem.iconpack") : base;
        int highSize = high.iconSize();
        if (high != base) {
            check(highSize == 256,
                    "Pressed uses the predominant 256px appfilter canvas, not outliers or preview pictures: " + highSize);
            IconFilters.Position position = new IconFilters.Position();
            position.set(0, 75);
            position.set(1, 15);
            position.set(3, 20);
            verifyPositionExport(context, high, List.of(position), false);
        }
        IconRepository.Pack combined = IconRepository.combine(List.of(base, high));
        int size = Math.max(baseSize, highSize);
        check(combined.iconSize() == size && IconRepository.combine(List.of(high, base)).iconSize() == size,
                "Combined packs use the largest source canvas regardless of priority");
        IconRepository.Pack pack256 = null;
        List<String> largest = new java.util.ArrayList<>();
        for (IconRepository.Icon icon : high.icons.values()) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            bounds.inScaled = false;
            BitmapFactory.decodeResource(high.resources, icon.id(), bounds);
            int nativeSize = Math.max(bounds.outWidth, bounds.outHeight);
            if (nativeSize > highSize && largest.size() < 3) largest.add(icon.name() + " "
                    + bounds.outWidth + "x" + bounds.outHeight + " mapped=" + high.mappings.containsValue(icon.name()));
            if (nativeSize == 256 && pack256 == null) {
                pack256 = new IconRepository.Pack(high.packageName, high.label, high.resources, high.badge, high.manager);
                pack256.icons.put(icon.name(), icon);
                pack256.byPackage.put("dev.test.nativeicons", icon.name());
            }
            if (pack256 != null && !largest.isEmpty()) break;
        }
        if (high != base) check(pack256 != null && pack256.iconSize() == 256 && !largest.isEmpty(),
                "Raw resource headers confirm 256px icons and larger non-icon catalogue pictures in Pressed");
        android.os.Bundle report = new android.os.Bundle();
        report.putString("stream", "Native resolution scan: " + base.label + "=" + baseSize + ", " + high.label
                + "=" + highSize + "; " + (android.os.SystemClock.elapsedRealtime() - start)
                + " ms; excluded catalogue resources: " + largest + "\n");
        sendStatus(0, report);
        File directory = new File(context.getCacheDir(), "native-resolution-check");
        Files.createDirectories(directory.toPath());
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
        };
        Bitmap original = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xB04080FF);
        new android.graphics.Canvas(original).drawCircle(256, 256, 220, paint);
        paint.setColor(android.graphics.Color.RED);
        new android.graphics.Canvas(original).drawRect(220, 220, 292, 292, paint);
        Bitmap cover = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
        File source = new File(directory, "source.png");
        try {
            try (FileOutputStream stream = new FileOutputStream(source)) {
                original.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            for (Bitmap imported : List.of(IconOverlay.read(isolated, Uri.fromFile(source)),
                    IconBackground.read(isolated, Uri.fromFile(source)), IconMask.read(isolated, Uri.fromFile(source)))) {
                try { check(imported.sameAs(original), "Layer imports preserve 512px source pixels and alpha"); }
                finally { imported.recycle(); }
            }
            String reference = FileIcons.save(isolated, original);
            combined.fileIcons = FileIcons.directory(isolated);
            Bitmap ring = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
            ring.eraseColor(Color.RED);
            Bitmap shrunk = IconRepository.Pack.scaleArtwork(ring, 80);
            Bitmap grown = IconRepository.Pack.scaleArtwork(shrunk.copy(Bitmap.Config.ARGB_8888, false), 125);
            try {
                check(ring.isRecycled() && shrunk.getWidth() == 200 && Color.alpha(shrunk.getPixel(10, 10)) == 0
                                && Color.alpha(shrunk.getPixel(30, 100)) == 255 && shrunk.getPixel(100, 100) == Color.RED,
                        "Icon scale keeps the canvas and leaves transparent margins around shrunk artwork");
                check(Color.alpha(grown.getPixel(3, 3)) == 255,
                        "Icon scale above 100% enlarges artwork to the canvas edges");
            } finally { shrunk.recycle(); grown.recycle(); }
            Bitmap same = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            check(IconRepository.Pack.scaleArtwork(same, 100) == same, "Icon scale 100% does not copy the bitmap");
            same.recycle();
            Draft draft = new Draft();
            draft.packs.add(high.packageName);
            draft.shape = IconShape.FILE;
            draft.shapeFile = reference;
            draft.maskScale = 130;
            draft.overlay = reference;
            draft.overlayScale = 120;
            draft.background = reference;
            draft.backgroundScale = 80;
            draft.iconScale = 85;
            TemplateStore store = new TemplateStore(isolated);
            TemplateStore.Entry saved = store.save(draft, high.label, false);
            Draft restored = store.open(saved.id());
            check(restored.iconScale == 85 && !new Draft().toJson().has("iconScale"),
                    "Templates persist icon scale and omit the 100% default");
            try {
                Draft.fromJson(new org.json.JSONObject().put("iconScale", 49));
                check(false, "Saved icon scale below 50% is rejected");
            } catch (org.json.JSONException expected) { check(true, "Saved icon scale below 50% is rejected"); }
            Bitmap restoredSource = FileIcons.render(FileIcons.directory(isolated), restored.shapeFile, 512);
            try { check(restoredSource.sameAs(original), "Templates preserve the full-resolution layer source"); }
            finally { restoredSource.recycle(); }
            Bitmap overlay = IconOverlay.load(isolated, restored.overlay, restored.overlayScale, size);
            Bitmap background = IconBackground.load(isolated, restored.background, restored.backgroundScale, size);
            Bitmap mask = IconMask.load(isolated, restored.shapeFile, restored.maskScale, size);
            Bitmap previewMask = IconMask.load(isolated, restored.shapeFile, restored.maskScale);
            try {
                check(overlay.getWidth() == size && background.getWidth() == size && mask.getWidth() == size
                                && previewMask.getWidth() == IconShape.SIZE,
                        "Export loads original layers at the pack maximum while previews retain lightweight canvases");
                if (pack256 != null) {
                    ThemeEngine.Built built = ThemeEngine.build(isolated, "256px canvas", true, IconShape.FILE,
                            restored.corners, false, pack256, List.of(), Map.of(), cover, null, overlay, false, true,
                            background, false, true, mask, (done, total) -> {});
                    try (var zip = new java.util.zip.ZipFile(built.file());
                         var icons = new java.util.zip.ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                        int entries = 0;
                        for (var entry = icons.getNextEntry(); entry != null; entry = icons.getNextEntry()) {
                            byte[] bytes = icons.readAllBytes();
                            if (entry.getName().equals("transparent_config.xml")) continue;
                            BitmapFactory.Options bounds = new BitmapFactory.Options();
                            bounds.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                            check(bounds.outWidth == 256 && bounds.outHeight == 256,
                                    "Full export normalizes high-resolution layers to a 256px-only pack canvas");
                            entries++;
                        }
                        check(entries == 4, "256px full export includes icon, overlay, background and mask");
                    } finally { Files.deleteIfExists(built.file().toPath()); }
                }
                IconRepository.App app = new IconRepository.App(new ComponentName("dev.test.nativeicons",
                        "dev.test.nativeicons.Main"), "Native resolution");
                String icon = IconRepository.reference(base.packageName, base.icons.keySet().iterator().next());
                Map<String, String> overrides = Map.of(app.key(), icon);
                ThemeEngine.Built manual = ThemeEngine.build(isolated, "Manual high resolution", false, IconShape.FILE,
                        restored.corners, false, combined, List.of(app), Map.of(app.key(), reference), cover, null,
                        overlay, true, true, background, true, true, mask, (done, total) -> {});
                try (var zip = new java.util.zip.ZipFile(manual.file());
                     var icons = new java.util.zip.ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                    for (var entry = icons.getNextEntry(); entry != null; entry = icons.getNextEntry()) {
                        byte[] bytes = icons.readAllBytes();
                        if (entry.getName().equals("transparent_config.xml")) continue;
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                        check(bounds.outWidth == size && bounds.outHeight == size,
                                "Manual high-resolution icon is fitted to the pack canvas with every exported layer");
                    }
                } finally { Files.deleteIfExists(manual.file().toPath()); }
                ThemeEngine.Built scaled = ThemeEngine.build(isolated, "Scaled icons", false, IconShape.FILE,
                        restored.corners, false, combined, List.of(app), Map.of(app.key(), reference), cover, null,
                        null, false, true, null, false, true, mask, 50, (done, total) -> {});
                try (var zip = new java.util.zip.ZipFile(scaled.file());
                     var icons = new java.util.zip.ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                    int checked = 0;
                    for (var entry = icons.getNextEntry(); entry != null; entry = icons.getNextEntry()) {
                        byte[] bytes = icons.readAllBytes();
                        if (entry.getName().startsWith("icon_") || entry.getName().equals("transparent_config.xml")) continue;
                        Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        try {
                            check(actual.getWidth() == size && Color.alpha(actual.getPixel(size / 5, size / 2)) == 0
                                            && Color.red(actual.getPixel(size / 2, size / 2)) > 200,
                                    "Icon scale 50% shrinks replacement artwork inside the exported canvas");
                        } finally { actual.recycle(); }
                        checked++;
                    }
                    check(checked == 1, "Scaled export contains the replaced app icon");
                } finally { Files.deleteIfExists(scaled.file().toPath()); }
                try {
                    ThemeEngine.build(isolated, "Invalid scale", false, IconShape.FILE, restored.corners, false, combined,
                            List.of(app), Map.of(app.key(), reference), cover, null, null, false, true, null, false, true,
                            mask, 151, (done, total) -> {});
                    check(false, "Icon scale above 150% is rejected");
                } catch (java.io.IOException expected) { check(true, "Icon scale above 150% is rejected"); }
                for (IconShape shape : List.of(IconShape.FILE, IconShape.CUSTOM)) {
                    for (boolean all : new boolean[]{false, true}) {
                        Bitmap border = IconOverlay.border(overlay, shape, restored.corners, combined.fallbackSize(), true, mask);
                        Bitmap backdrop = IconBackground.render(background, shape, restored.corners,
                                combined.fallbackSize(), true, mask);
                        Bitmap expectedMask = shape.mask(combined.fallbackSize(), restored.corners, mask, size);
                        ThemeEngine.Built built = null;
                        try {
                            built = ThemeEngine.build(isolated, "Native resolution", false, shape, restored.corners, false,
                                    combined, List.of(app), overrides, cover, null, overlay, all, true,
                                    background, all, true, mask, (done, total) -> {});
                            ThemeEngine.verifyArchive(built.file(), 1, shape, restored.corners, false,
                                    combined.fallbackSize(), false, border, backdrop, mask, size);
                            try (var zip = new java.util.zip.ZipFile(built.file());
                                 var icons = new java.util.zip.ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                                for (var entry = icons.getNextEntry(); entry != null; entry = icons.getNextEntry()) {
                                    byte[] bytes = icons.readAllBytes();
                                    if (entry.getName().equals("transparent_config.xml")) continue;
                                    Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                                    check(actual != null, "Export entry is a readable PNG");
                                    try {
                                        check(actual.getWidth() == size && actual.getHeight() == size,
                                                "Every exported icon and technical layer shares the maximum pack resolution");
                                        Bitmap expected = switch (entry.getName()) {
                                            case "icon_border.png" -> border.copy(Bitmap.Config.ARGB_8888, true);
                                            case "icon_background.png" -> backdrop.copy(Bitmap.Config.ARGB_8888, true);
                                            case "icon_mask.png" -> expectedMask.copy(Bitmap.Config.ARGB_8888, true);
                                            default -> {
                                                Bitmap rendered = combined.render(icon, size);
                                                if (all) {
                                                    IconOverlay.draw(rendered, overlay, true);
                                                    IconBackground.draw(rendered, backdrop);
                                                }
                                                yield rendered;
                                            }
                                        };
                                        try { check(actual.sameAs(expected), "Export preserves scaled layer alignment: "
                                                + shape + "/" + all + "/" + entry.getName()); }
                                        finally { expected.recycle(); }
                                    } finally { actual.recycle(); }
                                }
                            }
                        } finally {
                            border.recycle(); backdrop.recycle(); expectedMask.recycle();
                            if (built != null) Files.deleteIfExists(built.file().toPath());
                        }
                    }
                }
            } finally { overlay.recycle(); background.recycle(); mask.recycle(); previewMask.recycle(); }
        } finally {
            original.recycle(); cover.recycle();
            try (var files = Files.walk(directory.toPath())) {
                for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private void verifyOverlay(Context context, IconRepository.Pack pack) throws Exception {
        File directory = new File(context.getCacheDir(), "overlay-check");
        Files.createDirectories(directory.toPath());
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
        };
        Bitmap overlay = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(overlay);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.RED);
        canvas.drawRect(0, 0, 192, 12, paint);
        paint.setColor(0x800000FF);
        canvas.drawRect(80, 80, 112, 112, paint);
        Bitmap cover = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
        Bitmap opaque = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        opaque.eraseColor(android.graphics.Color.RED);
        Bitmap blank = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        File previousDirectory = pack.fileIcons;
        try {
            expectFailure(() -> IconOverlay.validate(opaque), "Opaque overlay rejected");
            expectFailure(() -> IconOverlay.validate(blank), "Invisible overlay rejected");
            File source = new File(directory, "overlay.png");
            try (FileOutputStream stream = new FileOutputStream(source)) {
                overlay.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            Bitmap imported = IconOverlay.read(isolated, Uri.fromFile(source));
            try { check(imported.sameAs(overlay), "PNG overlay preserves alpha and pixels without clipping"); }
            finally { imported.recycle(); }
            File rectangle = new File(directory, "rectangle.png");
            Bitmap wide = Bitmap.createBitmap(192, 96, Bitmap.Config.ARGB_8888);
            try (FileOutputStream stream = new FileOutputStream(rectangle)) {
                wide.compress(Bitmap.CompressFormat.PNG, 100, stream);
            } finally { wide.recycle(); }
            expectFailure(() -> IconOverlay.read(isolated, Uri.fromFile(rectangle)), "Non-square overlay rejected");
            File jpeg = new File(directory, "overlay.jpg");
            try (FileOutputStream stream = new FileOutputStream(jpeg)) {
                opaque.compress(Bitmap.CompressFormat.JPEG, 95, stream);
            }
            expectFailure(() -> IconOverlay.read(isolated, Uri.fromFile(jpeg)), "Non-PNG overlay rejected");
            String reference = FileIcons.save(isolated, overlay);
            Draft draft = new Draft();
            draft.packs.add(pack.packageName); draft.overlay = reference; draft.overlayAll = true;
            Draft roundTrip = Draft.fromJson(draft.toJson());
            check(roundTrip.overlay.equals(reference) && roundTrip.overlayAll, "Overlay and target scope round-trip");
            check(roundTrip.overlayScale == 100 && !draft.toJson().has("overlayScale"),
                    "Legacy overlay templates keep 100 percent scale and unchanged fingerprints");
            draft.overlayScale = 120;
            check(Draft.fromJson(draft.toJson()).overlayScale == 120, "Overlay scale round-trips in draft");
            for (int invalid : new int[]{49, 151}) {
                expectFailure(() -> Draft.fromJson(draft.toJson().put("overlayScale", invalid)),
                        "Saved overlay scale outside supported range is rejected");
                expectFailure(() -> IconOverlay.scaled(overlay, invalid), "Rendering rejects invalid overlay scale");
            }
            check(!new Draft().toJson().has("overlay"), "No-overlay drafts preserve legacy fingerprints");
            TemplateStore store = new TemplateStore(isolated);
            TemplateStore.Entry saved = store.save(draft, pack.label, false);
            check(saved.fileIcons().has(reference), "Template embeds overlay PNG bytes");
            File cached = FileIcons.directory(isolated).listFiles()[0];
            Files.delete(cached.toPath());
            Draft restored = store.open(saved.id());
            check(restored.overlayScale == 120, "Template restores overlay scale together with original PNG");
            Bitmap restoredImage = IconOverlay.load(isolated, restored.overlay);
            try { check(restoredImage.sameAs(overlay), "Template restores overlay after original file is removed"); }
            finally { restoredImage.recycle(); }
            Bitmap padded = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            android.graphics.Paint paddedPaint = new android.graphics.Paint();
            paddedPaint.setColor(0xB0FFFFFF);
            new android.graphics.Canvas(padded).drawRect(24, 24, 168, 168, paddedPaint);
            String paddedReference = FileIcons.save(isolated, padded);
            Bitmap scaled = IconOverlay.load(isolated, paddedReference, 120);
            Bitmap reduced = IconOverlay.load(isolated, paddedReference, 50);
            try {
                var bounds = IconRepository.Pack.visibleBounds(scaled);
                check(scaled.getWidth() == 192 && scaled.getHeight() == 192
                                && Math.abs(bounds.width() - 173) <= 1
                                && Math.abs(bounds.height() - 173) <= 1
                                && bounds.centerX() == 96 && bounds.centerY() == 96,
                        "120 percent enlarges padded artwork by 20 percent on the same centred 192px canvas");
                check(IconRepository.Pack.visibleBounds(reduced).width() == 72,
                        "50 percent overlay preserves centred transparent margins");
                Bitmap sourceAgain = IconOverlay.load(isolated, paddedReference);
                try { check(sourceAgain.sameAs(padded), "Scaling never modifies the stored original overlay"); }
                finally { sourceAgain.recycle(); }
                IconRepository.App scaledApp = new IconRepository.App(
                        new ComponentName("dev.test.scaled", "dev.test.scaled.Main"), "Scaled");
                Map<String, String> scaledOverrides = Map.of(scaledApp.key(), pack.icons.keySet().iterator().next());
                ThemeEngine.Built scaledTheme = ThemeEngine.build(isolated, "Scaled overlay", false,
                        IconShape.SQUARE, false, pack, List.of(scaledApp), scaledOverrides,
                        cover, null, scaled, true, (done, total) -> {});
                try (ZipFile archive = new ZipFile(scaledTheme.file());
                     ZipInputStream icons = new ZipInputStream(archive.getInputStream(archive.getEntry("icons")))) {
                    boolean borderFound = false, appFound = false;
                    for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                        if (!entry.getName().equals("icon_border.png") && !entry.getName().equals("dev.test.scaled.png")) continue;
                        byte[] bytes = icons.readAllBytes();
                        Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        Bitmap expected = entry.getName().equals("icon_border.png") ? scaled.copy(Bitmap.Config.ARGB_8888, true)
                                : pack.render(scaledOverrides.get(scaledApp.key()), 192);
                        try {
                            if (!entry.getName().equals("icon_border.png")) IconOverlay.draw(expected, scaled);
                            check(actual.sameAs(expected), "Export uses scaled artwork for border and baked icons");
                        } finally { actual.recycle(); expected.recycle(); }
                        borderFound |= entry.getName().equals("icon_border.png");
                        appFound |= entry.getName().equals("dev.test.scaled.png");
                    }
                    check(borderFound && appFound, "Scaled overlay archive contains native border and application icon");
                } finally { Files.deleteIfExists(scaledTheme.file().toPath()); }
                Bitmap full = IconOverlay.load(isolated, paddedReference, 150);
                try {
                    expectFailure(() -> IconOverlay.validate(full), "150 percent padded overlay has no transparent source pixel");
                    int[] corners = {100, 100, 100, 100};
                    Bitmap clippedBorder = IconOverlay.border(full, IconShape.CUSTOM, corners, pack.fallbackSize(), true);
                    Bitmap plainBorder = IconOverlay.border(full, IconShape.CUSTOM, corners, pack.fallbackSize(), false);
                    try {
                        check(plainBorder.sameAs(full), "Unclipped border keeps the scaled overlay unchanged");
                        check(android.graphics.Color.alpha(clippedBorder.getPixel(2, 2)) == 0
                                        && android.graphics.Color.alpha(clippedBorder.getPixel(96, 96)) > 0,
                                "Clipped border is hidden outside the fallback shape and kept inside it");
                    } finally { clippedBorder.recycle(); plainBorder.recycle(); }
                    ThemeEngine.Built fullTheme = ThemeEngine.build(isolated, "Clipped overlay", false,
                            IconShape.CUSTOM, corners, false, pack, List.of(scaledApp), scaledOverrides,
                            cover, null, full, true, true, (done, total) -> {});
                    try (ZipFile archive = new ZipFile(fullTheme.file());
                         ZipInputStream icons = new ZipInputStream(archive.getInputStream(archive.getEntry("icons")))) {
                        boolean borderFound = false, appFound = false;
                        for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                            byte[] bytes = icons.readAllBytes();
                            if (entry.getName().equals("icon_border.png")) {
                                Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                                Bitmap expected = IconOverlay.border(full, IconShape.CUSTOM, corners, pack.fallbackSize(), true);
                                try { check(actual.sameAs(expected), "150 percent overlay exports a shape-clipped border"); }
                                finally { actual.recycle(); expected.recycle(); }
                                borderFound = true;
                            } else if (entry.getName().equals("dev.test.scaled.png")) {
                                Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                                Bitmap packIcon = pack.render(scaledOverrides.get(scaledApp.key()), 192);
                                try {
                                    boolean inside = true;
                                    for (int y = 0; y < 192; y++) for (int x = 0; x < 192; x++) {
                                        if (android.graphics.Color.alpha(packIcon.getPixel(x, y)) == 0
                                                && android.graphics.Color.alpha(actual.getPixel(x, y)) != 0) inside = false;
                                    }
                                    check(inside, "Clipped baked overlay never paints outside the pack icon");
                                } finally { actual.recycle(); packIcon.recycle(); }
                                appFound = true;
                            }
                        }
                        check(borderFound && appFound, "150 percent clipped overlay theme builds successfully");
                    } finally { Files.deleteIfExists(fullTheme.file().toPath()); }
                } finally { full.recycle(); }
                List<IconRepository.App> examples = ShapePreview.examples(new IconRepository(context).apps(),
                        pack, Map.of(), java.util.Locale.ENGLISH);
                try (ShapePreview normalScene = ShapePreview.load(isolated, examples, pack.fallbackSize(), "", paddedReference);
                     ShapePreview scaledScene = ShapePreview.load(isolated, examples, pack.fallbackSize(), "", paddedReference, 120)) {
                    Bitmap normal = normalScene.render(IconShape.SQUARE), enlarged = scaledScene.render(IconShape.SQUARE);
                    try { check(!normal.sameAs(enlarged), "Shape popup uses the configured overlay scale"); }
                    finally { normal.recycle(); enlarged.recycle(); }
                }
            } finally { padded.recycle(); scaled.recycle(); reduced.recycle(); }
            IconRepository.App app = new IconRepository.App(new ComponentName("dev.test.overlay", "dev.test.overlay.Main"), "Overlay");
            String chosen = pack.icons.keySet().iterator().next();
            String fileIcon = FileIcons.save(isolated, opaque);
            pack.fileIcons = FileIcons.directory(isolated);
            for (boolean all : new boolean[]{false, true}) {
                for (String icon : List.of(chosen, fileIcon)) {
                    IconShape shape = icon.equals(chosen) ? IconShape.NONE : IconShape.CIRCLE;
                    Map<String, String> overrides = Map.of(app.key(), icon);
                    ThemeEngine.Built built = ThemeEngine.build(isolated, "Overlay check", false, shape, false,
                            pack, List.of(app), overrides, cover, null, overlay, all, (done, total) -> {});
                    try (ZipFile archive = new ZipFile(built.file());
                         ZipInputStream icons = new ZipInputStream(archive.getInputStream(archive.getEntry("icons")))) {
                        verifyFolderGlass(built.file());
                        boolean found = false;
                        for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                            if (!entry.getName().equals(app.component().getPackageName() + ".png")) continue;
                            byte[] bytes = icons.readAllBytes();
                            Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            Bitmap expected = pack.render(icon, 192);
                            try {
                                if (all) IconOverlay.draw(expected, overlay);
                                check(actual.sameAs(expected), "Overlay scope controls exported pack icon pixels exactly once");
                            } finally { actual.recycle(); expected.recycle(); }
                            found = true;
                        }
                        check(found, "Overlay archive includes requested icon");
                        expectFailure(() -> ThemeEngine.verifyArchive(built.file(), 1, shape, false),
                                "Archive validation rejects an unexpected border");
                    } finally { Files.deleteIfExists(built.file().toPath()); }
                }
            }
        } finally {
            pack.fileIcons = previousDirectory;
            overlay.recycle(); cover.recycle(); opaque.recycle(); blank.recycle();
            File[] files = FileIcons.directory(isolated).listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(FileIcons.directory(isolated).toPath());
            File[] artifacts = directory.listFiles();
            if (artifacts != null) for (File file : artifacts) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private void verifyBackground(Context context, IconRepository.Pack pack) throws Exception {
        File directory = new File(context.getCacheDir(), "background-check");
        Files.createDirectories(directory.toPath());
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
        };
        Bitmap background = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        background.eraseColor(android.graphics.Color.GREEN);
        Bitmap source = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.RED);
        new android.graphics.Canvas(source).drawRect(64, 64, 128, 128, paint);
        Bitmap overlay = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        paint.setColor(0x800000FF);
        new android.graphics.Canvas(overlay).drawRect(0, 0, 192, 180, paint);
        Bitmap blank = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        Bitmap cover = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
        File previousDirectory = pack.fileIcons;
        try {
            File input = new File(directory, "background.png");
            try (FileOutputStream stream = new FileOutputStream(input)) {
                background.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            Bitmap imported = IconBackground.read(isolated, Uri.fromFile(input));
            try { check(imported.sameAs(background), "Opaque PNG is a valid icon background"); }
            finally { imported.recycle(); }
            expectFailure(() -> IconBackground.validate(blank), "Empty backgrounds are rejected");
            Bitmap under = source.copy(Bitmap.Config.ARGB_8888, true);
            Bitmap expectedUnder = background.copy(Bitmap.Config.ARGB_8888, true);
            try {
                new android.graphics.Canvas(expectedUnder).drawBitmap(source, 0, 0, null);
                IconBackground.draw(under, background);
                check(under.sameAs(expectedUnder) && under.getPixel(96, 96) == android.graphics.Color.RED
                                && under.getPixel(0, 0) == android.graphics.Color.GREEN,
                        "Background is composed underneath, never covering the original icon");
            } finally { under.recycle(); expectedUnder.recycle(); }
            Bitmap noMask = IconBackground.render(background, IconShape.NONE, null, 172, true);
            try { check(noMask.sameAs(background), "No mask leaves the background outline unchanged"); }
            finally { noMask.recycle(); }
            File jpeg = new File(directory, "background.jpg");
            try (FileOutputStream stream = new FileOutputStream(jpeg)) {
                background.compress(Bitmap.CompressFormat.JPEG, 95, stream);
            }
            expectFailure(() -> IconBackground.read(isolated, Uri.fromFile(jpeg)), "Non-PNG backgrounds are rejected");
            Bitmap cornerOnly = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            cornerOnly.setPixel(0, 0, android.graphics.Color.GREEN);
            Bitmap clippedEmpty = IconBackground.render(cornerOnly, IconShape.CIRCLE, null, 172, true);
            try { expectFailure(() -> IconBackground.validate(clippedEmpty), "A completely clipped-away background is rejected"); }
            finally { cornerOnly.recycle(); clippedEmpty.recycle(); }
            String reference = FileIcons.save(isolated, background);
            Draft draft = new Draft();
            check(!draft.toJson().has("background") && draft.backgroundClip && draft.backgroundScale == 100,
                    "Old templates have no background and keep their JSON format");
            draft.background = reference; draft.backgroundAll = true; draft.backgroundClip = false; draft.backgroundScale = 150;
            check(draft.hasUnsavedChanges(isolated), "A background-only draft is not treated as empty");
            Draft restored = Draft.fromJson(draft.toJson());
            check(restored.background.equals(reference) && restored.backgroundAll
                            && !restored.backgroundClip && restored.backgroundScale == 150,
                    "Background source, scope, scale and clipping round-trip");
            for (int invalid : new int[]{49, 151}) {
                expectFailure(() -> Draft.fromJson(draft.toJson().put("backgroundScale", invalid)),
                        "Invalid saved background scale is rejected");
                expectFailure(() -> IconBackground.load(isolated, reference, invalid),
                        "Invalid background render scale is rejected");
            }
            TemplateStore store = new TemplateStore(isolated);
            TemplateStore.Entry saved = store.save(draft, pack.label, false);
            check(saved.fileIcons().has(reference), "Template embeds original background bytes");
            File stored = new File(FileIcons.directory(isolated), IconRepository.resourceName(reference) + ".png");
            Files.delete(stored.toPath());
            Draft reopened = store.open(saved.id());
            Bitmap restoredImage = IconBackground.load(isolated, reopened.background);
            try { check(restoredImage.sameAs(background), "Template restores the original background after deletion"); }
            finally { restoredImage.recycle(); }
            String iconReference = FileIcons.save(isolated, source);
            pack.fileIcons = FileIcons.directory(isolated);
            IconRepository.App app = new IconRepository.App(
                    new ComponentName("dev.test.background", "dev.test.background.Main"), "Background");
            Map<String, String> overrides = Map.of(app.key(), iconReference);
            Bitmap smaller = IconBackground.load(isolated, reference, 50);
            try {
                check(IconRepository.Pack.visibleBounds(smaller).width() == 96
                                && IconRepository.Pack.visibleBounds(smaller).centerX() == 96,
                        "50 percent background occupies a centred half-size square");
            } finally { smaller.recycle(); }
            for (boolean all : new boolean[]{false, true}) for (boolean clip : new boolean[]{false, true}) {
                IconShape shape = IconShape.CUSTOM;
                int[] corners = {100, 100, 100, 100};
                Bitmap scaled = IconBackground.load(isolated, reference, 150);
                Bitmap backdrop = IconBackground.render(scaled, shape, corners, pack.fallbackSize(), clip);
                Bitmap activeOverlay = all && clip ? overlay : null;
                Bitmap border = activeOverlay == null ? blank.copy(Bitmap.Config.ARGB_8888, true)
                        : IconOverlay.border(activeOverlay, shape, corners, pack.fallbackSize(), true);
                ThemeEngine.Built built = ThemeEngine.build(isolated, "Background check", false, shape, corners,
                        false, pack, List.of(app), overrides, cover, null, activeOverlay, true, true,
                        scaled, all, clip, (done, total) -> {});
                try (ZipFile archive = new ZipFile(built.file());
                     ZipInputStream icons = new ZipInputStream(archive.getInputStream(archive.getEntry("icons")))) {
                    boolean foundIcon = false, foundBackground = false, foundBorder = false;
                    for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                        String name = entry.getName();
                        if (!List.of("dev.test.background.png", "icon_background.png", "icon_border.png").contains(name)) continue;
                        byte[] bytes = icons.readAllBytes();
                        Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        Bitmap expected = name.equals("icon_background.png") ? backdrop.copy(Bitmap.Config.ARGB_8888, true)
                                : name.equals("icon_border.png") ? border.copy(Bitmap.Config.ARGB_8888, true)
                                : source.copy(Bitmap.Config.ARGB_8888, true);
                        try {
                            if (name.equals("dev.test.background.png")) {
                                IconOverlay.draw(expected, activeOverlay, true);
                                if (all) IconBackground.draw(expected, backdrop);
                                foundIcon = true;
                                if (all && clip) check(actual.getPixel(96, 32) == android.graphics.Color.GREEN,
                                        "Overlay clipping uses original icon alpha, not the added background");
                            }
                            foundBackground |= name.equals("icon_background.png");
                            foundBorder |= name.equals("icon_border.png");
                            check(actual.sameAs(expected), "Theme background composition and scope: " + all + "/" + clip + "/" + name);
                        } finally { actual.recycle(); expected.recycle(); }
                    }
                    check(foundIcon && foundBackground && foundBorder,
                            "Background export contains native background, border and application icon");
                    check(android.graphics.Color.alpha(backdrop.getPixel(0, 0)) == (clip ? 0 : 255),
                            "Background clipping follows the selected custom shape");
                    expectFailure(() -> ThemeEngine.verifyArchive(built.file(), 1, shape, corners, false,
                                    pack.fallbackSize(), false, border),
                            "Archive verification rejects an unexpected background");
                    Bitmap with = ThemeEngine.cover(pack, List.of(app), overrides, activeOverlay, true, backdrop);
                    Bitmap without = ThemeEngine.cover(pack, List.of(app), overrides, activeOverlay, true);
                    try { check(!with.sameAs(without), "Auto-cover displays the icon background"); }
                    finally { with.recycle(); without.recycle(); }
                } finally {
                    Files.deleteIfExists(built.file().toPath());
                    scaled.recycle(); backdrop.recycle(); border.recycle();
                }
            }
        } finally {
            pack.fileIcons = previousDirectory;
            background.recycle(); source.recycle(); overlay.recycle(); blank.recycle(); cover.recycle();
            File[] files = FileIcons.directory(isolated).listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(FileIcons.directory(isolated).toPath());
            File[] artifacts = directory.listFiles();
            if (artifacts != null) for (File file : artifacts) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private IconRepository.Pack positionFixture(Context context) {
        IconRepository.Pack pack = new IconRepository.Pack(context.getPackageName(), "Position fixture",
                context.getResources(), null, context.getPackageManager());
        pack.icons.put("fixture_icon", new IconRepository.Icon("fixture_icon", "", R.drawable.app_icon));
        pack.byPackage.put("dev.position.future", "fixture_icon");
        setField(pack, "iconSize", 192);
        setField(pack, "fallbackSize", 176);
        return pack;
    }

    private void verifyPositionFilter(Context context) throws Exception {
        IconFilters.Position position = (IconFilters.Position) IconFilters.create("position");
        check(position.neutral() && !IconFilters.active(List.of(position))
                        && java.util.Arrays.equals(position.values, new int[]{100, 0, 0, 0, 0}),
                "Position defaults are neutral");
        for (int i = 0; i < position.fields.length; i++) {
            int field = i;
            expectFailure(() -> position.set(field, position.min[field] - 1), "Position rejects value below range");
            expectFailure(() -> position.set(field, position.max[field] + 1), "Position rejects value above range");
        }
        expectFailure(() -> IconFilters.fromJson(new org.json.JSONArray(
                "[{\"type\":\"position\",\"scale\":151}]")), "Position rejects an invalid saved scale");
        expectFailure(() -> IconFilters.fromJson(new org.json.JSONArray(
                "[{\"type\":\"position\",\"offsetX\":101}]")), "Position rejects an invalid saved offset");
        expectFailure(() -> IconFilters.fromJson(new org.json.JSONArray(
                "[{\"type\":\"position\",\"skewY\":-46}]")), "Position rejects an invalid saved skew");
        expectFailure(() -> IconFilters.fromJson(new org.json.JSONArray(
                "[{\"type\":\"position\",\"scale\":100.5}]")), "Position rejects non-integer values");
        expectFailure(() -> IconFilters.fromJson(new org.json.JSONArray(
                "[{\"type\":\"position\",\"rotation\":1}]")), "Position rejects unknown fields");

        position.set(0, 75);
        position.set(1, 15);
        position.set(2, -10);
        position.set(3, 20);
        position.set(4, -12);
        IconFilters.ColorCorrection color = new IconFilters.ColorCorrection();
        color.set(2, -100);
        List<IconFilters.Filter> chain = List.of(color, position);
        List<IconFilters.Filter> restored = IconFilters.fromJson(IconFilters.toJson(chain));
        check(IconFilters.key(restored).equals(IconFilters.key(chain))
                        && restored.get(0) instanceof IconFilters.ColorCorrection
                        && restored.get(1) instanceof IconFilters.Position,
                "Mixed filter chain round-trips with its order and all position parameters");
        List<IconFilters.Filter> copied = IconFilters.copy(chain);
        position.set(1, 16);
        check(((IconFilters.Position) copied.get(1)).values[1] == 15, "Position copies do not share values");
        position.set(1, 15);

        Draft draft = new Draft();
        draft.filters.addAll(IconFilters.copy(chain));
        check(IconFilters.key(Draft.fromJson(draft.toJson()).filters).equals(IconFilters.key(chain)),
                "Draft preserves a mixed position filter chain");
        File directory = Files.createTempDirectory(context.getCacheDir().toPath(), "position-template-").toFile();
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
        };
        try {
            TemplateStore store = new TemplateStore(isolated);
            TemplateStore.Entry saved = store.save(draft, "Position test", true);
            check(IconFilters.key(store.open(saved.id()).filters).equals(IconFilters.key(chain)),
                    "Saved template restores position and filter order");
        } finally {
            for (String name : List.of("templates.json", "templates.json.new", "templates.json.bak"))
                Files.deleteIfExists(new File(directory, name).toPath());
            Files.deleteIfExists(directory.toPath());
        }

        Bitmap source = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        android.graphics.Paint red = new android.graphics.Paint();
        red.setColor(Color.RED);
        new android.graphics.Canvas(source).drawRect(48, 48, 144, 144, red);
        Bitmap original = source.copy(Bitmap.Config.ARGB_8888, false);
        try {
            IconFilters.Position scale = new IconFilters.Position();
            scale.set(0, 50);
            Bitmap small = IconShape.CIRCLE.preview(source, 176, null, null, List.of(scale));
            try {
                android.graphics.Rect bounds = IconRepository.Pack.visibleBounds(small);
                check(Math.abs(bounds.width() - 96) <= 1 && Math.abs(bounds.height() - 96) <= 1
                                && Math.abs(bounds.centerX() - 96) <= 1 && Math.abs(bounds.centerY() - 96) <= 1,
                        "50 percent position scale remains centered inside the shape");
            } finally { small.recycle(); }
            scale.set(1, 20);
            Bitmap shiftedX = IconShape.CIRCLE.preview(source, 176, null, null, List.of(scale));
            try {
                android.graphics.Rect bounds = IconRepository.Pack.visibleBounds(shiftedX);
                check(Math.abs(bounds.centerX() - 134) <= 2 && Math.abs(bounds.centerY() - 96) <= 1,
                        "Positive X offset remains visible after shape normalization");
            } finally { shiftedX.recycle(); }
            scale.set(1, 0);
            scale.set(2, -20);
            Bitmap shiftedY = IconShape.CIRCLE.preview(source, 176, null, null, List.of(scale));
            try {
                android.graphics.Rect bounds = IconRepository.Pack.visibleBounds(shiftedY);
                check(Math.abs(bounds.centerX() - 96) <= 1 && Math.abs(bounds.centerY() - 58) <= 2,
                        "Negative Y offset moves upward without being recentered");
            } finally { shiftedY.recycle(); }
            scale.set(1, 10);
            scale.set(2, 0);
            for (int size : new int[]{192, 384}) {
                Bitmap sized = IconShape.CIRCLE.preview(source, 176, null, null, size, List.of(scale));
                try {
                    android.graphics.Rect bounds = IconRepository.Pack.visibleBounds(sized);
                    check(Math.abs(bounds.width() - size / 2) <= 2
                                    && Math.abs(bounds.centerX() - size * .6f) <= 2,
                            "Position percentages use the same proportions at " + size + " px");
                } finally { sized.recycle(); }
            }
            Bitmap noMask = IconShape.NONE.preview(source, 176, null, null, List.of(scale));
            try {
                android.graphics.Rect bounds = IconRepository.Pack.visibleBounds(noMask);
                check(Math.abs(bounds.width() - 48) <= 1 && Math.abs(bounds.centerX() - 115) <= 2,
                        "Position also works when no shape is selected");
            } finally { noMask.recycle(); }
            check(source.sameAs(original), "Preparing a positioned preview does not modify its source");

            IconFilters.ColorCorrection grey = new IconFilters.ColorCorrection();
            grey.set(2, -100);
            Bitmap filtered = source.copy(Bitmap.Config.ARGB_8888, true);
            try {
                grey.apply(filtered);
                Bitmap before = IconShape.CIRCLE.preview(filtered, 176, null, null);
                Bitmap after = IconShape.CIRCLE.preview(source, 176, null, null,
                        List.of(grey, new IconFilters.Position()));
                try { check(before.sameAs(after), "Neutral position preserves existing color-only rendering"); }
                finally { before.recycle(); after.recycle(); }
            } finally { filtered.recycle(); }

            scale.set(1, 0);
            Bitmap icon = IconShape.CIRCLE.preview(source, 176, null, null, List.of(scale));
            Bitmap background = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            background.eraseColor(Color.BLUE);
            Bitmap overlay = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            android.graphics.Paint green = new android.graphics.Paint();
            green.setColor(Color.GREEN);
            new android.graphics.Canvas(overlay).drawRect(86, 20, 106, 32, green);
            Bitmap border = IconOverlay.border(overlay, IconShape.CIRCLE, null, 176, false);
            Bitmap backdrop = IconBackground.render(background, IconShape.CIRCLE, null, 176, true);
            try {
                IconOverlay.draw(icon, border);
                IconBackground.draw(icon, backdrop);
                check(icon.getPixel(96, 25) == Color.GREEN && icon.getPixel(96, 40) == Color.BLUE
                                && icon.getPixel(96, 96) == Color.RED && Color.alpha(icon.getPixel(0, 0)) == 0,
                        "Position changes the icon while the mask, overlay and background stay fixed");
            } finally {
                icon.recycle(); background.recycle(); overlay.recycle(); border.recycle(); backdrop.recycle();
            }
        } finally { source.recycle(); original.recycle(); }

        Bitmap block = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(block).drawRect(40, 40, 60, 60, red);
        try {
            IconFilters.Position skewX = new IconFilters.Position();
            skewX.set(3, 45);
            Bitmap x = block.copy(Bitmap.Config.ARGB_8888, true);
            try {
                skewX.apply(x);
                check(Color.alpha(x.getPixel(35, 42)) > 0 && Color.alpha(x.getPixel(65, 42)) == 0
                                && Color.alpha(x.getPixel(65, 57)) > 0 && Color.alpha(x.getPixel(35, 57)) == 0,
                        "Positive X skew shifts the top left and the bottom right");
            } finally { x.recycle(); }
            IconFilters.Position skewY = new IconFilters.Position();
            skewY.set(4, 45);
            Bitmap y = block.copy(Bitmap.Config.ARGB_8888, true);
            try {
                skewY.apply(y);
                check(Color.alpha(y.getPixel(42, 35)) > 0 && Color.alpha(y.getPixel(42, 65)) == 0
                                && Color.alpha(y.getPixel(57, 65)) > 0 && Color.alpha(y.getPixel(57, 35)) == 0,
                        "Positive Y skew shifts the left up and the right down");
            } finally { y.recycle(); }
            skewX.set(4, 45);
            Bitmap both = block.copy(Bitmap.Config.ARGB_8888, true);
            try {
                skewX.apply(both);
                int pixels = 0;
                for (int py = 0; py < 100; py++) for (int px = 0; px < 100; px++)
                    if (Color.alpha(both.getPixel(px, py)) >= 128) pixels++;
                check(pixels >= 300, "Combined extreme X/Y skews do not collapse into a line");
            } finally { both.recycle(); }
        } finally { block.recycle(); }

        Bitmap full = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        full.eraseColor(Color.RED);
        IconFilters.Position larger = new IconFilters.Position(), smaller = new IconFilters.Position();
        larger.set(0, 150);
        smaller.set(0, 50);
        Bitmap largeFirst = full.copy(Bitmap.Config.ARGB_8888, true);
        Bitmap smallFirst = full.copy(Bitmap.Config.ARGB_8888, true);
        try {
            IconFilters.apply(List.of(larger, smaller), largeFirst);
            IconFilters.apply(List.of(smaller, larger), smallFirst);
            int firstWidth = IconRepository.Pack.visibleBounds(largeFirst).width();
            int secondWidth = IconRepository.Pack.visibleBounds(smallFirst).width();
            check(Math.abs(firstWidth - 50) <= 2 && Math.abs(secondWidth - 75) <= 2,
                    "Position filters apply in chain order with per-step clipping");
        } finally { full.recycle(); largeFirst.recycle(); smallFirst.recycle(); }

        verifyPositionExport(context, positionFixture(context), chain, true);
    }

    private void verifyPositionExport(Context context, IconRepository.Pack pack,
                                      List<IconFilters.Filter> filters, boolean fixture) throws Exception {
        IconRepository.App self = new IconRepository.App(new ComponentName(context.getPackageName(),
                MainActivity.class.getName()), "Icon Foundry");
        Map<String, String> noReplacement = Map.of(self.key(), "");
        Map<String, String> generated = ThemeEngine.withFilteredSystemIcons(List.of(self), pack, noReplacement, filters);
        check((ThemeEngine.SYSTEM_ICON + self.key()).equals(generated.get(self.key())),
                "An active position generates an icon for an installed app without a replacement");
        String assigned = pack.icons.keySet().iterator().next();
        Map<String, String> manual = Map.of(self.key(), assigned);
        check(assigned.equals(ThemeEngine.withFilteredSystemIcons(List.of(self), pack, manual, filters).get(self.key())),
                "Position does not replace a manually assigned icon");
        check("".equals(ThemeEngine.withFilteredSystemIcons(List.of(self), pack, noReplacement,
                        List.of(new IconFilters.Position())).get(self.key())),
                "A neutral position does not generate an icon");

        int size = pack.iconSize(), visibleSize = pack.fallbackSize();
        Bitmap cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Bitmap background = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        background.eraseColor(Color.BLUE);
        Bitmap overlay = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.GREEN);
        new android.graphics.Canvas(overlay).drawRect(size * .4f, size * .1f, size * .6f, size * .2f, paint);
        Bitmap source = IconShape.previewSource(IconRepository.unthemedIcon(context.getPackageManager(),
                self.component()), true, size);
        Bitmap expected = IconShape.CIRCLE.preview(source, visibleSize, null, null, size, filters);
        Bitmap border = IconOverlay.border(overlay, IconShape.CIRCLE, null, visibleSize, true);
        Bitmap backdrop = IconBackground.render(background, IconShape.CIRCLE, null, visibleSize, true);
        try {
            IconOverlay.draw(expected, border);
            IconBackground.draw(expected, backdrop);
            for (boolean fullPack : fixture ? new boolean[]{false, true} : new boolean[]{false}) {
                ThemeEngine.Built built = ThemeEngine.build(context, "Position export", fullPack, IconShape.CIRCLE,
                        null, false, pack, List.of(self), noReplacement, cover, null, overlay, false, true,
                        background, false, true, null, 100, filters, (done, total) -> {});
                try (ZipFile archive = new ZipFile(built.file());
                     ZipInputStream icons = new ZipInputStream(archive.getInputStream(archive.getEntry("icons")))) {
                    boolean foundSystem = false, foundPack = false;
                    for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                        boolean system = entry.getName().equals(context.getPackageName() + ".png");
                        boolean future = entry.getName().equals("dev.position.future.png");
                        if (!system && !future) continue;
                        byte[] bytes = icons.readAllBytes();
                        Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        try {
                            if (system) {
                                foundSystem = true;
                                check(actual.getWidth() == size && actual.getHeight() == size && actual.sameAs(expected),
                                        "Exported position, shape and layers match the preview at " + size + " px");
                            } else {
                                foundPack = true;
                                Bitmap packIcon = pack.render("fixture_icon", size);
                                try { check(actual.sameAs(packIcon), "Position does not affect a future pack icon"); }
                                finally { packIcon.recycle(); }
                            }
                        } finally { actual.recycle(); }
                    }
                    check(foundSystem && foundPack == fullPack, "Position export respects installed and full-pack modes");
                } finally { Files.deleteIfExists(built.file().toPath()); }
            }
            try (ShapePreview before = ShapePreview.load(context, List.of(self), visibleSize, "", "");
                 ShapePreview after = ShapePreview.load(context, List.of(self), visibleSize, "", "",
                         100, "", "", filters)) {
                Bitmap plain = before.render(IconShape.CIRCLE), positioned = after.render(IconShape.CIRCLE);
                try { check(!plain.sameAs(positioned), "Shape picker preview reflects the position filter"); }
                finally { plain.recycle(); positioned.recycle(); }
            }
        } finally {
            cover.recycle(); background.recycle(); overlay.recycle(); source.recycle(); expected.recycle();
            border.recycle(); backdrop.recycle();
        }
    }

    private void verifyIconFilters(Context context, IconRepository.Pack pack) throws Exception {
        IconFilters.ColorCorrection neutral = (IconFilters.ColorCorrection) IconFilters.create("color");
        check(neutral.neutral() && !IconFilters.active(List.of(neutral)), "Default color correction is neutral");
        Bitmap sample = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888);
        sample.setPixel(0, 0, android.graphics.Color.RED);
        sample.setPixel(1, 0, android.graphics.Color.argb(128, 20, 200, 90));
        sample.setPixel(2, 0, android.graphics.Color.BLUE);
        Bitmap copy = sample.copy(Bitmap.Config.ARGB_8888, true);
        try {
            IconFilters.apply(List.of(neutral), copy);
            check(copy.sameAs(sample), "Neutral filter leaves pixels unchanged");
            IconFilters.ColorCorrection grey = (IconFilters.ColorCorrection) IconFilters.create("color");
            grey.set(2, -100);
            IconFilters.apply(List.of(grey), copy);
            for (int x = 0; x < 3; x++) {
                int pixel = copy.getPixel(x, 0);
                check(Math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.green(pixel)) <= 1
                                && Math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.blue(pixel)) <= 1,
                        "Saturation -100 produces grey pixels");
            }
            check(android.graphics.Color.alpha(copy.getPixel(1, 0)) == 128, "Saturation keeps alpha");
            IconFilters.ColorCorrection transparent = (IconFilters.ColorCorrection) IconFilters.create("color");
            transparent.set(7, 50);
            IconFilters.apply(List.of(transparent), copy);
            check(Math.abs(android.graphics.Color.alpha(copy.getPixel(0, 0)) - 128) <= 1, "Opacity scales alpha");
            IconFilters.ColorCorrection order = (IconFilters.ColorCorrection) IconFilters.create("color");
            order.set(0, 40);
            List<IconFilters.Filter> chain = List.of(grey, order);
            List<IconFilters.Filter> restored = IconFilters.fromJson(IconFilters.toJson(chain));
            check(IconFilters.key(restored).equals(IconFilters.key(chain)), "Filter chain survives JSON with order");
            Draft draft = new Draft();
            draft.filters.addAll(chain);
            check(IconFilters.key(Draft.fromJson(draft.toJson()).filters).equals(IconFilters.key(chain)),
                    "Draft saves filter chain");
            check(!new Draft().toJson().has("iconFilters"), "Drafts without filters keep their old JSON");
            expectFailure(() -> IconFilters.fromJson(new org.json.JSONArray(
                    "[{\"type\":\"blur\"}]")), "Unknown filter type is rejected");
            expectFailure(() -> IconFilters.fromJson(new org.json.JSONArray(
                    "[{\"type\":\"color\",\"brightness\":101}]")), "Out-of-range filter value is rejected");
            expectFailure(() -> IconFilters.fromJson(new org.json.JSONArray(
                    "[{\"type\":\"color\",\"gamma\":1}]")), "Unknown filter field is rejected");
        } finally { sample.recycle(); copy.recycle(); }

        IconRepository.App self = new IconRepository.App(new ComponentName(context.getPackageName(),
                MainActivity.class.getName()), "Icon Foundry");
        check(pack.selected(self, Map.of()) == null, "Filter export fixture has no pack icon");
        Bitmap cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        IconFilters.ColorCorrection grey = (IconFilters.ColorCorrection) IconFilters.create("color");
        grey.set(2, -100);
        try {
            expectFailure(() -> ThemeEngine.build(context, "Filter check", false, IconShape.CIRCLE, null,
                    false, pack, List.of(self), Map.of(), cover, null, null, false, true, null, false, true,
                    null, 100, List.of(neutral), (done, total) -> {}), "Neutral filters do not export system icons");
            for (boolean filtered : new boolean[]{true}) {
                ThemeEngine.Built built = ThemeEngine.build(context, "Filter check", false, IconShape.CIRCLE, null,
                        false, pack, List.of(self), Map.of(), cover, null, null, false, true, null, false, true,
                        null, 100, List.of(grey), (done, total) -> {});
                try (ZipFile archive = new ZipFile(built.file());
                     ZipInputStream icons = new ZipInputStream(archive.getInputStream(archive.getEntry("icons")))) {
                    boolean found = false;
                    for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                        if (!entry.getName().startsWith(context.getPackageName())) continue;
                        found = true;
                        byte[] bytes = icons.readAllBytes();
                        Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        try {
                            int centre = actual.getPixel(actual.getWidth() / 2, actual.getHeight() / 2);
                            check(android.graphics.Color.alpha(centre) > 0
                                            && android.graphics.Color.red(centre) == android.graphics.Color.green(centre)
                                            && android.graphics.Color.alpha(actual.getPixel(0, 0)) == 0,
                                    "Filtered system icon is grey and receives the fallback shape");
                        } finally { actual.recycle(); }
                    }
                    check(found == filtered, "Only active filters export system icons: " + filtered);
                } finally { Files.deleteIfExists(built.file().toPath()); }
            }
        } finally { cover.recycle(); }
    }

    private void verifyFiltersUi(MainActivity activity) throws Exception {
        Draft original = (Draft) field(activity, "draft");
        Draft isolated = Draft.fromJson(original.toJson());
        isolated.filters.clear();
        var build = MainActivity.class.getDeclaredMethod("buildScreen");
        build.setAccessible(true);
        try {
            runOnMainChecked(() -> {
                setField(activity, "draft", isolated);
                build.invoke(activity);
                android.view.View decor = activity.getWindow().getDecorView();
                decor.findViewWithTag("editor-tab-1").performClick();
                android.widget.Button button = decor.findViewWithTag("style-tab-3");
                check(button != null && button.getText().toString().equals(activity.getString(R.string.filters)),
                        "Filters have a dedicated Style tab");
                button.performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                check(((android.widget.ImageView) field(activity, "stylePreview")).getDrawable() != null,
                        "Filters use the common pinned preview");
                decor.findViewWithTag("filter-add").performClick();
                android.app.AlertDialog chooser = (android.app.AlertDialog) field(activity, "filterTypeDialog");
                chooser.getListView().performItemClick(null, 0, 0);
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                android.widget.ImageView example = (android.widget.ImageView) field(activity, "stylePreview");
                check(example.getDrawable() != null, "Filter preview renders a system icon");
                Bitmap before = ((android.graphics.drawable.BitmapDrawable) example.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                android.widget.SeekBar saturation = decor.findViewWithTag("filter-0-saturation");
                check(saturation != null && saturation.getMin() == -100 && saturation.getProgress() == 0,
                        "Color correction exposes a saturation slider");
                saturation.setProgress(-99);
                saturation.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT));
                check(saturation.getProgress() == -100, "Filter slider steps by one");
                previewBefore = before;
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                android.widget.ImageView example = (android.widget.ImageView) field(activity, "stylePreview");
                try { check(!previewBefore.sameAs(((android.graphics.drawable.BitmapDrawable) example.getDrawable()).getBitmap()),
                        "Changing a filter updates the preview"); }
                finally { previewBefore.recycle(); previewBefore = null; }
                decor.findViewWithTag("filter-add").performClick();
                ((android.app.AlertDialog) field(activity, "filterTypeDialog")).getListView().performItemClick(null, 0, 0);
                check(!decor.findViewWithTag("filter-0-up").isEnabled() && decor.findViewWithTag("filter-0-down").isEnabled(),
                        "Only valid move buttons are enabled");
                decor.findViewWithTag("filter-0-down").performClick();
                check(((android.widget.SeekBar) decor.findViewWithTag("filter-1-saturation")).getProgress() == -100,
                        "Moving a filter changes the chain order");
                decor.findViewWithTag("filter-0-remove").performClick();
                check(decor.findViewWithTag("filter-1") == null, "Removing a filter updates the list");
                check(isolated.filters.size() == 1
                                && ((IconFilters.ColorCorrection) Draft.load(activity).filters.get(0)).values[2] == -100,
                        "Reordering, removal and slider edits save without confirmation");
                decor.findViewWithTag("filter-0-remove").performClick();
                check(isolated.filters.isEmpty() && Draft.load(activity).filters.isEmpty(),
                        "Removing the last filter saves an empty chain");
                decor.findViewWithTag("filter-add").performClick();
                ((android.app.AlertDialog) field(activity, "filterTypeDialog")).getListView().performItemClick(null, 0, 0);
                android.widget.SeekBar contrast = decor.findViewWithTag("filter-0-contrast");
                contrast.setProgress(29);
                contrast.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                check(isolated.filters.size() == 1 && Draft.load(activity).filters.size() == 1
                                && ((IconFilters.ColorCorrection) isolated.filters.get(0)).values[1] == 30,
                        "Editing filters saves the chain immediately");
                decor.findViewWithTag("style-tab-2").performClick();
                decor.findViewWithTag("style-tab-3").performClick();
                check(((android.widget.SeekBar) decor.findViewWithTag("filter-0-contrast")).getProgress() == 30,
                        "Switching tabs preserves the filter controls");
            });
            waitForThumbnails(activity);
        } finally {
            runOnMainChecked(() -> {
                setField(activity, "draft", original);
                original.save(activity); build.invoke(activity);
            });
            if (previewBefore != null) { previewBefore.recycle(); previewBefore = null; }
        }
    }

    private Bitmap previewBefore;

    private void verifyPositionUiFixture(Context context) throws Exception {
        MainActivity activity = (MainActivity) startActivitySync(new android.content.Intent(context, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            waitForWork(activity);
            IconRepository.Pack fixture = positionFixture(activity);
            IconRepository.App self = new IconRepository.App(new ComponentName(context.getPackageName(),
                    MainActivity.class.getName()), "Icon Foundry");
            runOnMainChecked(() -> {
                setField(activity, "pack", fixture);
                setField(activity, "apps", List.of(self));
                setField(activity, "editing", true);
                setField(activity, "draft", new Draft());
            });
            verifyPositionUi(activity);
        } finally {
            runOnMainChecked(activity::finish);
        }
    }

    private void verifyPositionUi(MainActivity activity) throws Exception {
        Draft original = (Draft) field(activity, "draft");
        Draft isolated = Draft.fromJson(original.toJson());
        isolated.filters.clear();
        var build = MainActivity.class.getDeclaredMethod("buildScreen");
        build.setAccessible(true);
        Bitmap[] before = {null};
        try {
            runOnMainChecked(() -> {
                setField(activity, "draft", isolated);
                build.invoke(activity);
                activity.getWindow().getDecorView().findViewWithTag("editor-tab-1").performClick();
                activity.getWindow().getDecorView().findViewWithTag("style-tab-3").performClick();
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                decor.findViewWithTag("filter-add").performClick();
                android.app.AlertDialog chooser = (android.app.AlertDialog) field(activity, "filterTypeDialog");
                check(chooser.getListView().getCount() == 2, "Filter menu offers color and position");
                chooser.getListView().performItemClick(null, 1, 1);
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                String[] fields = {"scale", "offsetX", "offsetY", "skewX", "skewY"};
                for (int i = 0; i < fields.length; i++) {
                    android.widget.SeekBar slider = decor.findViewWithTag("filter-0-" + fields[i]);
                    check(slider != null && slider.getMin() == IconFilters.Position.MIN[i]
                                    && slider.getMax() == IconFilters.Position.MAX[i]
                                    && slider.getProgress() == IconFilters.Position.DEFAULT[i],
                            "Position slider starts with the documented range and default: " + fields[i]);
                    String unit = i < 3 ? "%" : "°";
                    check(slider.getContentDescription().toString().contains(unit),
                            "Position slider announces its unit: " + fields[i]);
                }
                android.widget.ImageView example = (android.widget.ImageView) field(activity, "stylePreview");
                check(example.getDrawable() instanceof android.graphics.drawable.BitmapDrawable,
                        "Position editor displays a system icon");
                before[0] = ((android.graphics.drawable.BitmapDrawable) example.getDrawable()).getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
                stepPositionSlider(decor, "filter-0-scale", 75);
                stepPositionSlider(decor, "filter-0-offsetX", 20);
                stepPositionSlider(decor, "filter-0-offsetY", -10);
                stepPositionSlider(decor, "filter-0-skewX", 15);
                stepPositionSlider(decor, "filter-0-skewY", -15);
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                android.widget.ImageView example = (android.widget.ImageView) field(activity, "stylePreview");
                check(!before[0].sameAs(((android.graphics.drawable.BitmapDrawable) example.getDrawable()).getBitmap()),
                        "Position controls update the pinned preview");
                before[0].recycle();
                before[0] = null;
                decor.findViewWithTag("filter-add").performClick();
                ((android.app.AlertDialog) field(activity, "filterTypeDialog")).getListView().performItemClick(null, 0, 0);
                decor.findViewWithTag("filter-0-down").performClick();
                check(((android.widget.SeekBar) decor.findViewWithTag("filter-1-offsetX")).getProgress() == 20
                                && ((android.widget.SeekBar) decor.findViewWithTag("filter-1-skewY")).getProgress() == -15,
                        "Moving position after color preserves all settings");
                check(isolated.filters.size() == 2 && isolated.filters.get(0) instanceof IconFilters.ColorCorrection
                                && isolated.filters.get(1) instanceof IconFilters.Position
                                && java.util.Arrays.equals(((IconFilters.Position) isolated.filters.get(1)).values,
                                        new int[]{75, 20, -10, 15, -15})
                                && IconFilters.key(Draft.load(activity).filters).equals(IconFilters.key(isolated.filters)),
                        "Editing saves the mixed filter order and position settings");
                build.invoke(activity);
                android.view.View reopenedDecor = activity.getWindow().getDecorView();
                check(((android.widget.SeekBar) reopenedDecor.findViewWithTag("filter-1-scale")).getProgress() == 75
                                && ((android.widget.SeekBar) reopenedDecor.findViewWithTag("filter-1-offsetY")).getProgress() == -10,
                        "Rebuilding the editor restores the position controls");
                stepPositionSlider(reopenedDecor, "filter-1-scale", 76);
                reopenedDecor.findViewWithTag("style-tab-2").performClick();
                check(((IconFilters.Position) Draft.load(activity).filters.get(1)).values[0] == 76,
                        "Leaving Filters retains position changes without an Apply action");
            });
            waitForThumbnails(activity);
        } finally {
            runOnMainChecked(() -> {
                setField(activity, "draft", original);
                original.save(activity);
                build.invoke(activity);
            });
            if (before[0] != null) before[0].recycle();
        }
    }

    private void stepPositionSlider(android.view.View decor, String tag, int value) {
        android.widget.SeekBar slider = decor.findViewWithTag(tag);
        slider.setProgress(value - 1);
        decor.findViewWithTag(tag + "-increase").performClick();
        check(slider.getProgress() == value, "Position arrow changes the value by one: " + tag);
    }

    private void verifyFileIcons(Context context, IconRepository.Pack pack) throws Exception {
        File directory = new File(context.getCacheDir(), "file-icon-check");
        Files.createDirectories(directory.toPath());
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
        };
        Bitmap wide = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888);
        wide.eraseColor(android.graphics.Color.RED);
        File original = new File(directory, "input.png");
        try (FileOutputStream output = new FileOutputStream(original)) { wide.compress(Bitmap.CompressFormat.PNG, 100, output); }
        finally { wide.recycle(); }
        java.io.File previousDirectory = pack.fileIcons;
        Bitmap cover = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
        try {
            Bitmap imported = FileIcons.read(isolated, Uri.fromFile(original));
            String reference;
            try {
                check(imported.getWidth() == 192 && imported.getHeight() == 192,
                        "Imported file normalized to native export canvas");
                check(android.graphics.Color.alpha(imported.getPixel(96, 0)) == 0
                        && imported.getPixel(96, 96) == android.graphics.Color.RED,
                        "Rectangular file retains proportions, transparent padding and colors without cropping");
                reference = FileIcons.save(isolated, imported);
                check(reference.equals(FileIcons.save(isolated, imported)), "Identical file icons deduplicate by content");
            } finally { imported.recycle(); }
            Files.delete(original.toPath());
            pack.fileIcons = FileIcons.directory(isolated);
            Bitmap rendered = pack.render(reference, 192);
            try { check(rendered.getPixel(96, 96) == android.graphics.Color.RED, "File icon renders without original document"); }
            finally { rendered.recycle(); }
            IconRepository.App app = new IconRepository.App(new ComponentName("dev.test.file", "dev.test.file.Main"), "Test");
            Draft draft = new Draft();
            draft.packs.add(pack.packageName);
            draft.overrides.put(app.key(), reference);
            TemplateStore store = new TemplateStore(isolated);
            TemplateStore.Entry saved = store.save(draft, pack.label, false);
            check(saved.fileIcons().length() == 1 && saved.fileIcons().has(reference),
                    "Template embeds file image bytes, not just URI or path");
            File stored = new File(FileIcons.directory(isolated), IconRepository.resourceName(reference) + ".png");
            byte[] expected = Files.readAllBytes(stored.toPath());
            Files.delete(stored.toPath());
            Draft restored = store.open(saved.id());
            check(reference.equals(restored.overrides.get(app.key())) && java.util.Arrays.equals(expected, Files.readAllBytes(stored.toPath())),
                    "Template independently restores file icon after private working image is removed");
            for (boolean full : new boolean[]{false, true}) {
                ThemeEngine.Built built = ThemeEngine.build(context, "File icon check", full, IconShape.NONE, false,
                        pack, List.of(app), restored.overrides, cover, (done, total) -> {});
                try (ZipFile zip = new ZipFile(built.file());
                     ZipInputStream icons = new ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                    boolean found = false;
                    for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                        if (entry.getName().equals("dev.test.file.png")) {
                            check(java.util.Arrays.equals(expected, icons.readAllBytes()),
                                    "File icon pixels exported in installed-only and full-pack modes");
                            found = true;
                        }
                    }
                    check(found, "File override creates real application PNG in theme");
                } finally { Files.delete(built.file().toPath()); }
            }
            check(ThemeEngine.coverIcons(pack, List.of(app), Map.of(app.key(), reference)) != null,
                    "File reference does not break auto-cover selection");
            byte[] before = Files.readAllBytes(new File(directory, "templates.json").toPath());
            Files.delete(stored.toPath());
            expectFailure(() -> store.save(restored, pack.label, false), "Missing file icon cannot silently disappear from template");
            check(java.util.Arrays.equals(before, Files.readAllBytes(new File(directory, "templates.json").toPath())),
                    "Failed file icon save preserves existing template");
            store.open(saved.id());
            expectFailure(() -> FileIcons.restore(isolated, "fileicon:i" + "0".repeat(64), expected),
                    "Template file content hash is verified");
            expectFailure(() -> FileIcons.render(FileIcons.directory(isolated), "fileicon:../../outside", 192),
                    "File icon references reject traversal paths");
        } finally {
            pack.fileIcons = previousDirectory;
            cover.recycle();
            File icons = FileIcons.directory(isolated);
            if (icons.isDirectory()) {
                for (File file : icons.listFiles()) Files.delete(file.toPath());
                Files.delete(icons.toPath());
            }
            for (File file : directory.listFiles()) Files.delete(file.toPath());
            Files.delete(directory.toPath());
        }
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
            draft.packs.add(pack.packageName);
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

    private void verifyFileMask(Context context, IconRepository.Pack pack) throws Exception {
        File directory = new File(context.getCacheDir(), "icon-mask-check");
        Files.createDirectories(directory.toPath());
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
        };
        Bitmap source = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(android.graphics.Color.WHITE);
        android.graphics.Canvas canvas = new android.graphics.Canvas(source);
        canvas.drawCircle(96, 96, 86, paint);
        canvas.drawRect(25, 74, 167, 118, paint);
        File input = new File(directory, "mask.png");
        Bitmap mask = null, previewSource = null, preview = null, generated = null;
        ThemeEngine.Built built = null;
        try {
            try (FileOutputStream stream = new FileOutputStream(input)) {
                check(source.compress(Bitmap.CompressFormat.PNG, 100, stream), "Custom PNG mask fixture encoded");
            }
            mask = IconMask.read(isolated, Uri.fromFile(input));
            check(mask.getWidth() == IconShape.SIZE && mask.getHeight() == IconShape.SIZE,
                    "Imported custom mask is normalized to the icon canvas");
            String reference = FileIcons.save(isolated, mask);
            generated = IconShape.FILE.mask(pack.fallbackSize(), null, mask);
            int inset = (192 - pack.fallbackSize()) / 2;
            check((generated.getPixel(96, 96) & 0x00ffffff) == 0
                            && android.graphics.Color.alpha(generated.getPixel(96, 96)) == 255
                            && android.graphics.Color.alpha(generated.getPixel(8, 8)) == 0
                            && android.graphics.Color.alpha(generated.getPixel(96, inset + 20)) > 0,
                    "File mask preserves its alpha silhouette and pack-sized padding");
            previewSource = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            previewSource.eraseColor(android.graphics.Color.RED);
            preview = IconShape.FILE.preview(previewSource, pack.fallbackSize(), null, mask);
            check(preview.getPixel(96, 96) == android.graphics.Color.RED
                            && android.graphics.Color.alpha(preview.getPixel(8, 8)) == 0,
                    "Selected file mask clips icon previews using its alpha channel");
            expectFailure(() -> IconShape.FILE.mask(pack.fallbackSize(), null),
                    "File mask shape cannot be generated without its source PNG");
            Draft settings = new Draft();
            settings.shape = IconShape.FILE;
            settings.shapeFile = reference;
            check(Draft.fromJson(settings.toJson()).shapeFile.equals(reference),
                    "Template draft stores the selected mask reference");
            check(!settings.toJson().has("maskScale"), "Default mask scale is omitted from template JSON");
            settings.maskScale = 70;
            check(Draft.fromJson(settings.toJson()).maskScale == 70 && settings.toJson().getInt("maskScale") == 70,
                    "Template draft stores the mask scale");
            org.json.JSONObject tooLarge = settings.toJson().put("maskScale", 151);
            expectFailure(() -> Draft.fromJson(tooLarge), "Mask scale above 150% is rejected");
            org.json.JSONObject withoutFile = new Draft().toJson().put("maskScale", 80);
            expectFailure(() -> Draft.fromJson(withoutFile), "Mask scale without a PNG mask is rejected");
            expectFailure(() -> IconMask.load(isolated, reference, 49), "Mask scale below 50% is rejected when loading");
            Bitmap smaller = IconMask.load(isolated, reference, 70), larger = IconMask.load(isolated, reference, 150);
            try {
                check(android.graphics.Color.alpha(mask.getPixel(96, 14)) > 0
                                && android.graphics.Color.alpha(smaller.getPixel(96, 14)) == 0
                                && android.graphics.Color.alpha(smaller.getPixel(96, 96)) > 0,
                        "Smaller mask scale shrinks the silhouette around the canvas centre");
                check(android.graphics.Color.alpha(mask.getPixel(2, 96)) == 0
                                && android.graphics.Color.alpha(larger.getPixel(2, 96)) > 0
                                && larger.getWidth() == IconShape.SIZE,
                        "Larger mask scale enlarges the silhouette and crops it to the icon canvas");
                Bitmap scaledShape = IconShape.FILE.mask(pack.fallbackSize(), null, smaller);
                try { check(!scaledShape.sameAs(IconShape.FILE.mask(pack.fallbackSize(), null, mask)),
                        "Exported icon_mask.png follows the selected mask scale"); }
                finally { scaledShape.recycle(); }
            } finally { smaller.recycle(); larger.recycle(); }
            TemplateStore store = new TemplateStore(isolated);
            TemplateStore.Entry saved = store.save(settings, "Mask test", true);
            File stored = new File(FileIcons.directory(isolated),
                    reference.substring("fileicon:".length()) + ".png");
            Files.delete(stored.toPath());
            Draft restored = store.open(saved.id());
            Bitmap restoredMask = IconMask.load(isolated, restored.shapeFile);
            try {
                check(restored.shape == IconShape.FILE && restoredMask.sameAs(mask) && restored.maskScale == 70,
                        "Template embeds and restores the custom mask image");
            } finally { restoredMask.recycle(); }
            IconRepository.App app = new IconRepository.App(
                    new ComponentName("dev.test.custommask", "dev.test.custommask.Main"), "Custom mask");
            String icon = pack.icons.keySet().iterator().next();
            Bitmap cover = ThemeEngine.cover(pack, List.of(app), Map.of(app.key(), icon));
            try {
                built = ThemeEngine.build(context, "Custom file mask", false, IconShape.FILE, null, false,
                        pack, List.of(app), Map.of(app.key(), icon), cover, null, null, false, true,
                        null, false, true, mask, (done, total) -> {});
            } finally { cover.recycle(); }
            ThemeEngine.verifyArchive(built.file(), 1, IconShape.FILE, null, false, pack.fallbackSize(),
                    false, null, null, mask);
            try (ZipFile archive = new ZipFile(built.file());
                 ZipInputStream icons = new ZipInputStream(archive.getInputStream(archive.getEntry("icons")))) {
                boolean found = false;
                for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                    if (!entry.getName().equals("icon_mask.png")) continue;
                    byte[] bytes = icons.readAllBytes();
                    Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    try { check(actual.sameAs(generated), "Exported icon_mask.png matches the selected file silhouette"); }
                    finally { actual.recycle(); }
                    found = true;
                }
                check(found, "Custom mask export includes the native HONOR icon_mask.png asset");
            }
            Bitmap missingMaskCover = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            try {
                expectFailure(() -> ThemeEngine.build(context, "Missing custom mask", false, IconShape.FILE, null, false,
                        pack, List.of(app), Map.of(app.key(), icon), missingMaskCover, null, null, false, true,
                        null, false, true, (done, total) -> {}),
                        "Export refuses a file mask when its source image is missing");
            } finally { missingMaskCover.recycle(); }
            Bitmap empty = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            try { expectFailure(() -> IconMask.validate(empty), "Fully transparent mask files are rejected"); }
            finally { empty.recycle(); }
            Bitmap rectangle = Bitmap.createBitmap(192, 96, Bitmap.Config.ARGB_8888);
            File nonSquare = new File(directory, "nonsquare.png");
            try (FileOutputStream stream = new FileOutputStream(nonSquare)) {
                rectangle.eraseColor(android.graphics.Color.WHITE);
                rectangle.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            try { expectFailure(() -> IconMask.read(isolated, Uri.fromFile(nonSquare)), "Non-square mask PNG is rejected"); }
            finally { rectangle.recycle(); }
        } finally {
            source.recycle();
            if (mask != null) mask.recycle();
            if (previewSource != null) previewSource.recycle();
            if (preview != null) preview.recycle();
            if (generated != null) generated.recycle();
            if (built != null) Files.deleteIfExists(built.file().toPath());
            File[] files = FileIcons.directory(isolated).listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(FileIcons.directory(isolated).toPath());
            File[] artifacts = directory.listFiles();
            if (artifacts != null) for (File file : artifacts) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private void verifyShapes(Context context, IconRepository.Pack pack, IconRepository.App app, String icon) throws Exception {
        List<int[]> silhouettes = new ArrayList<>();
        Bitmap cover = ThemeEngine.cover(pack, List.of(app), Map.of(app.key(), icon));
        try {
            for (IconShape shape : IconShape.values()) {
                if (shape == IconShape.FILE) continue;
                boolean clearFolderBackground = shape.ordinal() % 2 == 0;
                ThemeEngine.Built built = ThemeEngine.build(context, "Shape check", false, shape, clearFolderBackground, pack,
                        List.of(app), Map.of(app.key(), icon), cover, (done, total) -> {});
                try {
                    ThemeEngine.verifyArchive(built.file(), 1, shape, clearFolderBackground, pack.fallbackSize());
                    verifyFolderGlass(built.file());
                    if (shape == IconShape.NONE) {
                        for (String replacement : new String[]{null, "<HnTheme><transparent>true</transparent></HnTheme>"}) {
                            File altered = replaceFolderGlass(context, built.file(), replacement);
                            try {
                                try {
                                    ThemeEngine.verifyArchive(altered, 1, shape, clearFolderBackground, pack.fallbackSize());
                                    throw new AssertionError("Invalid folder glass metadata was accepted");
                                } catch (java.io.IOException error) {
                                    check(App.text(R.string.folder_glass_error).equals(error.getMessage()),
                                            "Missing or altered folder glass metadata fails explicitly");
                                }
                            } finally { Files.deleteIfExists(altered.toPath()); }
                        }
                    }
                    try (ZipFile zip = new ZipFile(built.file())) {
                        check((zip.getEntry(ThemeEngine.LAUNCHER) != null) == clearFolderBackground,
                                "Folder background override appears only when enabled");
                    }
                    try (ZipFile zip = new ZipFile(built.file());
                         ZipInputStream icons = new ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                        boolean hasMask = false;
                        for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                            byte[] bytes = icons.readAllBytes();
                            if (entry.getName().equals("transparent_config.xml")) continue;
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
                                    check(black && padding && area > pack.fallbackSize() * pack.fallbackSize() / 4,
                                            "Mask is black silhouette with correct padding and substantial area: " + shape
                                            + " black=" + black + " padding=" + padding + " area=" + area);
                                    check(android.graphics.Color.alpha(actual.getPixel(96, 96)) == 255, "Mask centre is fully opaque");
                                    check(silhouettes.stream().noneMatch(previous -> java.util.Arrays.equals(previous, pixels)),
                                            "Every selectable shape has different pixels: " + shape);
                                    silhouettes.add(pixels);
                                    int inset = (192 - pack.fallbackSize()) / 2;
                                    check(android.graphics.Color.alpha(actual.getPixel(inset + 1, inset + 1)) == 0,
                                            "Mask corners retain their rounded or polygon silhouette");
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
        check(new Draft().shape == IconShape.ROUNDED_SQUARE && !new Draft().systemIcons,
                "New themes default to rounded square mask and hide system icons");
        Draft withSystem = Draft.fromJson(new org.json.JSONObject("{}"));
        check(!withSystem.toJson().has("systemIcons"), "Disabled system icon preview keeps old template format");
        withSystem.systemIcons = true;
        check(Draft.fromJson(withSystem.toJson()).systemIcons, "System icon preview choice is stored in template");
        check(Draft.load(testContext).clearFolderBackground, "Old drafts enable agreed folder background fix by default");
        android.content.SharedPreferences appDraft = context.getSharedPreferences("draft", 0);
        Map<String, ?> savedDraft = appDraft.getAll();
        Object savedJson = savedDraft.get("json");
        org.json.JSONObject isolatedDraft = new org.json.JSONObject()
                .put("tree", new org.json.JSONObject(savedJson instanceof String ? (String) savedJson : "{}")
                        .optString("tree", ""));
        if (!appDraft.edit().clear().putString("json", isolatedDraft.toString()).commit()) {
            throw new java.io.IOException("Could not isolate the device UI test draft");
        }
        android.app.Activity activity;
        try {
            activity = startActivitySync(new android.content.Intent(context, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (RuntimeException | Error error) {
            restorePreferences(appDraft, savedDraft);
            throw error;
        }
        try {
            runOnMainSync(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            waitForWork(activity);
            runOnMainChecked(() -> {
                check(findText(activity.getWindow().getDecorView(), context.getString(R.string.templates)) != null,
                        "Application starts with template list");
                check(field(activity, "pack") == null, "Start screen does not load an icon pack");
                ((MainActivity) activity).openEditor();
            });
            waitForWork(activity);
            runOnMainChecked(() -> {
                android.util.TypedValue accent = new android.util.TypedValue();
                activity.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
                check(accent.data == android.graphics.Color.rgb(255, 193, 7), "App theme uses amber accent");
                check(((android.widget.LinearLayout) field(activity, "packList")).getChildCount()
                                == ((Draft) field(activity, "draft")).packs.size()
                                && ((android.widget.LinearLayout) field(activity, "packList")).isLaidOut(),
                        "Template loader closes only after base packs are drawn");
                Draft shown = (Draft) field(activity, "draft");
                boolean manualCover = !shown.coverPath.isEmpty();
                check(((android.view.View) field(activity, "discardCover")).getVisibility()
                                == (manualCover ? android.view.View.VISIBLE : android.view.View.GONE)
                        && ((android.view.View) field(activity, "autoCoverInfo")).getVisibility()
                                == (manualCover ? android.view.View.GONE : android.view.View.VISIBLE),
                        "Auto-cover action only for chosen covers, otherwise automatic cover note");
                check(((android.widget.Button) field(activity, "destinationButton")).getText().toString().equals(
                                activity.getString(shown.tree.isEmpty() ? R.string.choose_destination : R.string.change_destination)),
                        "Destination button offers changing an already chosen folder");
                check(shown.wallpaperPath.isEmpty() == (((android.view.View) field(activity, "wallpaperPreview")).getVisibility()
                                == android.view.View.GONE), "Wallpaper button spans full width without wallpaper");
                android.widget.LinearLayout root = (android.widget.LinearLayout) field(activity, "root");
                boolean night = (activity.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                check(((android.graphics.drawable.ColorDrawable) root.getBackground()).getColor()
                        == (night ? android.graphics.Color.BLACK : android.graphics.Color.rgb(245, 245, 245)),
                        "Main screen background follows the active appearance");
                android.widget.CheckBox folder = (android.widget.CheckBox) findText(
                        root, activity.getString(R.string.folder_background));
                check(folder != null && folder.isChecked() == ((Draft) field(activity, "draft")).clearFolderBackground,
                        "Folder background toggle reflects persisted setting");
                android.widget.Button shapePicker = (android.widget.Button) field(activity, "shapeButton");
                android.widget.ImageView styleImage = (android.widget.ImageView) field(activity, "stylePreview");
                check(styleImage.getScaleType() == android.widget.ImageView.ScaleType.CENTER_CROP
                                && styleImage.getTag() != null,
                        "Universal style preview fills the editor stage");
                check(shapePicker != null && shapePicker.getParent() == null
                                && root.findViewWithTag("style-shape-presets") != null,
                        "Shape selection uses the inline illustrated tabs, not a separate button");
                check(findShapePicker(activity.getWindow().getDecorView()) == null,
                        "Shape selection no longer uses a stock dropdown");
                check(findText(activity.getWindow().getDecorView(), "Выбрать образец .hnt") == null,
                        "Sample selection removed from real screen");
                android.widget.Button choose = (android.widget.Button) field(activity, "addPackButton");
                check(choose.getText().toString().equals(context.getString(
                        ((Draft) field(activity, "draft")).packs.isEmpty() ? R.string.choose_pack : R.string.add_pack)),
                        "Pack button says choose for the first pack and add for further packs");
                choose.performClick();
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "packDialog");
                check(dialog != null && dialog.isShowing(), "Pack picker opens synchronously before discovery completes");
                check(findText(dialog.getWindow().getDecorView(), "Понятно") == null,
                        "Pack picker has no redundant dismissal button");
                check(findText(dialog.getWindow().getDecorView(), context.getString(R.string.loading)) != null,
                        "Picker immediately shows loading state");
                check(progressCount(activity.getWindow().getDecorView()) == 0,
                        "Pack skeleton does not add a duplicate spinner to editor");
                check(activity.getString(R.string.ready).contentEquals(((android.widget.TextView) field(activity, "status")).getText()),
                        "Draft footer does not change while pack discovery is loading");
            });
            waitForWork(activity);
            runOnMainChecked(() -> {
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
                check(!(boolean) field(activity, "busy"), "Pack discovery finishes before picker is used");
            });
            verifyOperationUi((MainActivity) activity);
            runOnMainSync(() -> ((android.widget.Button) field(activity, "addPackButton")).performClick());
            waitForWork(activity);
            runOnMainSync(() -> {
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "packDialog");
                android.widget.ListView list = findList(dialog.getWindow().getDecorView());
                check(list != null && list.getAdapter().getCount() > 0, "Pack picker can select a pack for editor tests");
                list.performItemClick(list.getAdapter().getView(0, null, list), 0, list.getAdapter().getItemId(0));
            });
            waitForWork(activity);
            IconRepository.Pack editorPack = (IconRepository.Pack) field(activity, "pack");
            check(editorPack != null, "Editor tests start with a selected pack");
            IconRepository.Pack primaryPack = editorPack.members.isEmpty() ? editorPack : editorPack.members.get(0);
            verifyMultiPackUi((MainActivity) activity, primaryPack);
            verifyThemeEditing((MainActivity) activity, primaryPack);
            verifyWallpaperUi((MainActivity) activity);
            verifyFileIconUi((MainActivity) activity, primaryPack);
            verifyIconSourcePicker((MainActivity) activity, primaryPack);
            verifyCropUi((MainActivity) activity);
            verifyTemplateExportUi((MainActivity) activity);
        } finally {
            try { runOnMainSync(activity::finish); }
            finally { restorePreferences(appDraft, savedDraft); }
        }
    }

    private static void restorePreferences(android.content.SharedPreferences preferences, Map<String, ?> saved) {
        var editor = preferences.edit().clear();
        for (var entry : saved.entrySet()) editor.putString(entry.getKey(), (String) entry.getValue());
        if (!editor.commit()) throw new AssertionError("Could not restore the device test draft");
    }

    private void verifyTemplateExportUi(MainActivity activity) throws Exception {
        Draft original = (Draft) field(activity, "draft");
        TemplateStore originalStore = (TemplateStore) field(activity, "templates");
        Draft settings = Draft.fromJson(original.toJson());
        String templateTitle = "Template integration " + Long.toUnsignedString(System.nanoTime());
        String copyTitle = templateTitle + " copy";
        settings.templateId = "";
        settings.title = templateTitle;
        settings.coverPath = "";
        settings.wallpaperPath = "";
        settings.fullPack = false;
        settings.overrides.clear();
        settings.overlay = "";
        settings.overlayAll = false;
        settings.overlayScale = 100;
        settings.background = "";
        settings.backgroundAll = false;
        settings.backgroundScale = 100;
        settings.backgroundClip = true;
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
                int current = step;
                String themeTitle = current == 2 ? copyTitle : templateTitle;
                String expectedTitle = current == 1 ? themeTitle + " v2" : themeTitle;
                runOnMainSync(() -> {
                    ((android.widget.EditText) field(activity, "title")).setText(themeTitle);
                    ((android.widget.CheckBox) field(activity, "saveAsNew")).setChecked(current == 2);
                    findText(activity.getWindow().getDecorView(), activity.getString(R.string.export)).performClick();
                });
                waitForWork(activity);
                String[] actualName = {null};
                runOnMainSync(() -> {
                    android.app.AlertDialog dialog = (android.app.AlertDialog) field(activity, "exportDialog");
                    check(dialog != null && dialog.isShowing(), "Generated filename reported after successful export");
                    String message = ((android.widget.TextView) dialog.findViewById(android.R.id.message)).getText().toString();
                    check(message.startsWith("Honor/Themes/"), "Success identifies actual export directory");
                    actualName[0] = message.substring("Honor/Themes/".length()).split("\n", 2)[0];
                });
                String filename = actualName[0];
                filenames.add(filename);
                check(filename.matches(expectedTitle + "_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(_\\d+)?\\.hnt"),
                        "Real export automatically generates dated filename from unique display title");
                check(ThemeEngine.findDocument(activity.getContentResolver(), tree, filename) != null,
                        "Template integration writes verified .hnt through real export button");
                try (var input = activity.getContentResolver().openInputStream(
                        ThemeEngine.findDocument(activity.getContentResolver(), tree, filename));
                     ZipInputStream zip = new ZipInputStream(input)) {
                    boolean found = false;
                    for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                        if (entry.getName().equals("description.xml")) {
                            IconRepository.Pack used = (IconRepository.Pack) field(activity, "pack");
                            verifyDescription(zip.readAllBytes(), expectedTitle, ThemeEngine.credits(used.members.isEmpty()
                                    ? List.of(used.label) : used.members.stream().map(member -> member.label).toList()));
                            found = true;
                            break;
                        }
                    }
                    check(found, "Export contains unique theme metadata with untimestamped display title");
                }
                if (current == 1) {
                    runOnMainSync(() -> {
                        android.app.AlertDialog dialog = (android.app.AlertDialog) field(activity, "exportDialog");
                        String message = ((android.widget.TextView) dialog.findViewById(android.R.id.message)).getText().toString();
                        check(message.contains(activity.getString(R.string.export_display_title, expectedTitle)),
                                "Export result reports the automatically versioned theme title");
                    });
                }
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
            check(new java.util.HashSet<>(filenames).size() == 3,
                    "Repeated export of same title creates separate files rather than replacing an old theme");
            String originalId = firstId;
            runOnMainSync(() -> findText(activity.getWindow().getDecorView(),
                    activity.getString(R.string.templates)).performClick());
            waitForWork(activity);
            runOnMainSync(() -> {
                check(findText(activity.getWindow().getDecorView(), activity.getString(R.string.resume_draft)) == null,
                        "Unchanged draft saved by export does not offer to resume draft");
                check(findText(activity.getWindow().getDecorView(), activity.getString(R.string.ready)) == null,
                        "Template list does not show the draft autosave note");
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
                check(((android.widget.EditText) field(activity, "title")).getText().toString().equals(templateTitle),
                        "Opening older template restores its independent settings");
            });
            byte[] before = Files.readAllBytes(new File(directory, "templates.json").toPath());
            java.util.Set<String> documentsBeforeFailure = exportDocuments(activity, tree);
            runOnMainSync(() -> {
                Draft current = (Draft) field(activity, "draft");
                @SuppressWarnings("unchecked")
                List<IconRepository.App> apps = (List<IconRepository.App>) field(activity, "apps");
                current.overrides.put(apps.get(0).key(), "dev.missing.iconpack:missing");
                findText(activity.getWindow().getDecorView(), activity.getString(R.string.export)).performClick();
            });
            waitForWork(activity);
            check(java.util.Arrays.equals(before, Files.readAllBytes(new File(directory, "templates.json").toPath())),
                    "Failed icon resolution leaves previously saved templates byte-for-byte unchanged");
            check(exportDocuments(activity, tree).equals(documentsBeforeFailure),
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
                check(findText(activity.getWindow().getDecorView(), activity.getString(R.string.resume_draft)) != null,
                        "Changed draft not saved into template offers to resume draft");
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
            Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
            try (var cursor = activity.getContentResolver().query(children,
                    new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null) throw new java.io.IOException("Cannot enumerate temporary integration themes");
                while (cursor.moveToNext()) {
                    String filename = cursor.getString(0);
                    if (filename.startsWith(templateTitle) && filename.endsWith(".hnt") && !filenames.contains(filename))
                        filenames.add(filename);
                }
            }
            for (String filename : filenames) {
                Uri document = ThemeEngine.findDocument(activity.getContentResolver(), tree, filename);
                if (document != null) check(android.provider.DocumentsContract.deleteDocument(activity.getContentResolver(), document),
                        "Temporary integration theme removed without touching user themes");
            }
            Files.deleteIfExists(new File(directory, "templates.json").toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private java.util.Set<String> exportDocuments(Context context, Uri tree) throws Exception {
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
        java.util.Set<String> ids = new java.util.HashSet<>();
        try (var cursor = context.getContentResolver().query(children,
                new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cursor == null) throw new java.io.IOException("Cannot enumerate test export directory");
            while (cursor.moveToNext()) ids.add(cursor.getString(0));
        }
        return ids;
    }

    private void verifyDescription(byte[] xml, String title, String credits) throws Exception {
        var parser = android.util.Xml.newPullParser();
        parser.setInput(new java.io.ByteArrayInputStream(xml), "UTF-8");
        Map<String, String> fields = new LinkedHashMap<>();
        for (int event = parser.next(); event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.getDepth() == 2) {
                String name = parser.getName();
                fields.put(name, parser.nextText());
            }
        }
        check(title.equals(fields.get("title")) && title.equals(fields.get("title-cn")),
                "Display title remains unchanged and is properly XML-escaped, without filename timestamp");
        check("Icon Foundry by ToLive".equals(fields.get("author"))
                        && "Icon Foundry by ToLive".equals(fields.get("designer")),
                "Theme credits identify Icon Foundry by ToLive in author and designer fields");
        check(credits.startsWith("На основе иконпак") && credits.endsWith("Только для персонального использования.")
                        && credits.equals(fields.get("briefInfo")),
                "Description credits actual base pack and its developer, with personal-use restriction");
        check("12".equals(fields.get("version")), "Compatible theme revision retained without risky automatic upgrades");
    }

    private int progressCount(android.view.View view) {
        int count = view instanceof android.widget.ProgressBar progress && progress.isIndeterminate() ? 1 : 0;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) count += progressCount(group.getChildAt(i));
        }
        return count;
    }

    private void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void verifyPerPackResolution(Context context) throws Exception {
        IconRepository repository = new IconRepository(context);
        IconRepository.Pack first = repository.load("com.maxpatchs.premium");
        IconRepository.Pack second = repository.load("com.pressedprem.iconpack");
        String firstName = first.icons.keySet().iterator().next();
        String secondName = second.icons.keySet().iterator().next();
        IconRepository.App a = new IconRepository.App(new ComponentName("dev.test.a", "dev.test.a.Main"), "A");
        IconRepository.App b = new IconRepository.App(new ComponentName("dev.test.b", "dev.test.b.Main"), "B");
        IconRepository.Pack smallA = new IconRepository.Pack(first.packageName, first.label, first.resources, first.badge, first.manager);
        IconRepository.Pack smallB = new IconRepository.Pack(second.packageName, second.label, second.resources, second.badge, second.manager);
        smallA.icons.put(firstName, first.icons.get(firstName));
        smallA.mappings.put(a.key(), firstName);
        smallB.icons.put(secondName, second.icons.get(secondName));
        smallB.mappings.put(a.key(), secondName);
        smallB.mappings.put(b.key(), secondName);
        IconRepository.Pack combined = IconRepository.combine(List.of(smallA, smallB));
        Draft d = new Draft();
        d.packs.add(first.packageName); d.packs.add(second.packageName);
        check(Draft.fromJson(d.toJson()).exportSizes.isEmpty(), "Legacy draft keeps per-pack native defaults");
        ExportSizes nativeSizes = new ExportSizes(combined, Map.of());
        check(nativeSizes.forIcon(combined.automatic(a), combined.packageName) == smallA.iconSize()
                && nativeSizes.forIcon(combined.automatic(b), combined.packageName) == smallB.iconSize(),
                "Native defaults use each winning source rather than combined maximum");
        d.exportSizes.put(first.packageName, 256); d.exportSizes.put(second.packageName, 96);
        check(Draft.fromJson(d.toJson()).exportSizes.equals(d.exportSizes), "Per-pack sizes round trip");
        expectFailure(() -> Draft.fromJson(new org.json.JSONObject().put("exportSizes",
                new org.json.JSONObject().put(first.packageName, 123))), "Unsupported resolution rejected");
        for (Object bad : List.of("128", 128.5, true)) {
            expectFailure(() -> Draft.fromJson(new org.json.JSONObject().put("exportSizes",
                    new org.json.JSONObject().put(first.packageName, bad))), "Non-integer resolution rejected");
        }
        expectFailure(() -> Draft.fromJson(new org.json.JSONObject().put("exportSizes", 128)),
                "Malformed resolution map rejected");
        check(!ExportSizes.oversized(ExportSizes.WARNING_BYTES - 1)
                && !ExportSizes.oversized(ExportSizes.WARNING_BYTES)
                && ExportSizes.oversized(ExportSizes.WARNING_BYTES + 1), "Warning threshold is strictly above 290 MiB");
        ExportSizes reversed = new ExportSizes(IconRepository.combine(List.of(smallB, smallA)), d.exportSizes);
        check(reversed.forIcon(IconRepository.reference(first.packageName, firstName), second.packageName) == 256,
                "Reorder preserves source settings");
        File directory = Files.createTempDirectory(context.getCacheDir().toPath(), "pack-resolution-").toFile();
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
        };
        combined.fileIcons = FileIcons.directory(isolated);
        Bitmap layer = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        layer.eraseColor(Color.argb(80, 0, 160, 240));
        Bitmap fileImage = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        fileImage.eraseColor(Color.RED);
        Bitmap mask = IconShape.CIRCLE.mask(170, null, null, 256);
        Bitmap cover = null;
        try {
            TemplateStore store = new TemplateStore(isolated);
            TemplateStore.Entry entry = store.save(d, combined.label, true);
            Draft restored = store.open(entry.id());
            check(restored.exportSizes.equals(d.exportSizes), "Template store preserves resolutions");
            d.templateId = entry.id(); d.markSaved(context);
            check(!d.hasUnsavedChanges(context), "Saved resolution fingerprint is stable");
            d.exportSizes.put(second.packageName, 128);
            check(d.hasUnsavedChanges(context), "Resolution changes mark draft unsaved");
            String fileRef = FileIcons.save(isolated, fileImage);
            IconRepository.App fileApp = new IconRepository.App(new ComponentName("dev.test.file", "dev.test.file.Main"), "File");
            IconRepository.App external = new IconRepository.App(new ComponentName("dev.test.external", "dev.test.external.Main"), "External");
            IconRepository.App manual = new IconRepository.App(new ComponentName("dev.test.manual", "dev.test.manual.Main"), "Manual");
            IconRepository.App system = repository.apps().stream()
                    .filter(app -> app.component().getPackageName().equals(context.getPackageName())).findFirst().orElseThrow();
            Map<String, String> overrides = Map.of(fileApp.key(), fileRef,
                    external.key(), IconRepository.reference(context.getPackageName(), "app_icon"),
                    manual.key(), IconRepository.reference(second.packageName, secondName));
            List<IconRepository.App> apps = List.of(a, b, fileApp, external, manual, system);
            cover = ThemeEngine.cover(combined, apps, overrides);
            IconFilters.Position position = new IconFilters.Position();
            position.set(1, 10);
            for (int chosen : ExportSizes.OPTIONS) for (boolean full : List.of(false, true)) {
                Map<String, Integer> settings = Map.of(first.packageName, 256, second.packageName, chosen);
                ExportSizes sizes = new ExportSizes(combined, settings);
                Map<String, String> targets = ThemeEngine.exportTargets(apps, combined,
                        ThemeEngine.withFilteredSystemIcons(apps, combined, overrides, List.of(position)), full);
                Map<String, Integer> expected = new LinkedHashMap<>();
                targets.forEach((key, value) -> expected.put(key + ".png", sizes.forIcon(value, combined.packageName)));
                check(expected.get("dev.test.a.png") == 256 && expected.get("dev.test.b.png") == chosen
                        && expected.get("dev.test.manual.png") == chosen && expected.get("dev.test.file.png") == 256
                        && expected.get("dev.test.external.png") == 256, "Source resolution follows priority and manual source");
                ThemeEngine.Built built = ThemeEngine.build(isolated, "Mixed resolution", full, IconShape.FILE,
                        null, false, combined, apps, overrides, cover, null, layer, true, true,
                        layer, true, true, mask, 75, List.of(position), settings, (done, total) -> {});
                try (ZipFile zip = new ZipFile(built.file());
                     ZipInputStream icons = new ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
                    int found = 0;
                    for (ZipEntry icon; (icon = icons.getNextEntry()) != null;) {
                        if (!icon.getName().endsWith(".png")) continue;
                        byte[] bytes = icons.readAllBytes();
                        Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        try {
                            int size = expected.getOrDefault(icon.getName(), 256);
                            check(actual != null && actual.getWidth() == size && actual.getHeight() == size,
                                    "Mixed archive exact PNG dimensions: " + icon.getName());
                            if (icon.getName().equals("dev.test.b.png")) {
                                Bitmap wanted = IconRepository.Pack.scaleArtwork(combined.render(combined.automatic(b), chosen), 75);
                                Bitmap background = IconBackground.render(layer, IconShape.FILE, null,
                                        combined.fallbackSize(), true, mask);
                                try {
                                    IconOverlay.draw(wanted, layer, true);
                                    IconBackground.draw(wanted, background);
                                    check(actual.sameAs(wanted), "Small canvas preserves scale, clipping and fitted All layers");
                                } finally { wanted.recycle(); background.recycle(); }
                            }
                            found++;
                        } finally { if (actual != null) actual.recycle(); }
                    }
                    check(found == expected.size() + 3, "All targets and technical layers exported");
                } finally { Files.delete(built.file().toPath()); }
                expected.put("dev.test.b.png", chosen == 96 ? 128 : 96);
                // A wrong per-target expectation must fail, even though both sizes are supported.
                ThemeEngine.Built plain = ThemeEngine.build(isolated, "Strict resolution", full, IconShape.NONE,
                        null, false, combined, apps, overrides, cover, null, null, false, true,
                        null, false, true, null, 100, List.of(position), settings, (done, total) -> {});
                try {
                    expectFailure(() -> ThemeEngine.verifyArchive(plain.file(), expected.size(), IconShape.NONE,
                            null, false, 170, false, null, null, null, 256, expected),
                            "Archive validation rejects wrong target size");
                } finally { Files.delete(plain.file().toPath()); }
            }
            check(!ThemeEngine.withFilteredSystemIcons(apps, combined, overrides, List.of()).containsKey(system.key()),
                    "Neutral filters do not export system icons");
            for (int chosen : ExportSizes.OPTIONS) {
                ExportSizes single = new ExportSizes(first, Map.of(first.packageName, chosen));
                check(single.maximum == chosen && single.forIcon(firstName, first.packageName) == chosen,
                        "Single pack supports every explicit size");
            }
            verifySafReplacement(context, first);
            verifyPerPackResolutionUi(context, smallA, smallB);
        } finally {
            layer.recycle(); fileImage.recycle(); mask.recycle();
            if (cover != null) cover.recycle();
            try (var files = Files.walk(directory.toPath())) {
                for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private void verifyPerPackResolutionUi(Context context, IconRepository.Pack first,
                                           IconRepository.Pack second) throws Exception {
        MainActivity activity = (MainActivity) startActivitySync(new android.content.Intent(context, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            waitForWork(activity);
            var build = MainActivity.class.getDeclaredMethod("buildScreen");
            build.setAccessible(true);
            Draft fixture = new Draft();
            fixture.packs.add(first.packageName); fixture.packs.add(second.packageName);
            fixture.exportSizes.put(second.packageName, 128);
            runOnMainChecked(() -> {
                setField(activity, "draft", fixture);
                setField(activity, "pack", IconRepository.combine(List.of(first, second)));
                @SuppressWarnings("unchecked")
                List<IconRepository.Pack> packs = (List<IconRepository.Pack>) field(activity, "packs");
                packs.clear(); packs.add(first); packs.add(second);
                setField(activity, "editing", true);
                build.invoke(activity);
            });
            waitForThumbnails(activity);
            runOnMainChecked(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                android.widget.Button button = (android.widget.Button) decor.findViewWithTag("export-size:" + second.packageName);
                check(button.getText().toString().contains("128 px"), "Pack row restores chosen resolution");
                check(button.getMaxLines() == 1 && button.getLayout().getLineCount() == 1,
                        "Resolution button stays on one line");
                android.widget.LinearLayout rows = (android.widget.LinearLayout) field(activity, "packList");
                android.view.View remove = ((android.view.ViewGroup) rows.getChildAt(1)).getChildAt(3);
                check(remove instanceof android.widget.ImageButton && remove.getWidth() == remove.getHeight()
                        && remove.getContentDescription().toString().contains(second.label),
                        "Pack removal uses a square circular icon button with an accessible label");
                button.performClick();
                android.app.AlertDialog dialog = (android.app.AlertDialog) field(activity, "exportSizeDialog");
                check(dialog.getListView().getAdapter().getCount() == 5, "Picker offers native and four fixed sizes");
                dialog.getListView().performItemClick(null, 4, 4);
                check(fixture.exportSizes.get(second.packageName) == 96 && Draft.load(activity).exportSizes.equals(fixture.exportSizes),
                        "Choosing 96 saves pack resolution");
                activity.movePack(1, 0);
                check(fixture.packs.get(0).equals(second.packageName) && fixture.exportSizes.get(second.packageName) == 96,
                        "UI reorder keeps resolution bound to source package");
                decor.findViewWithTag("export-size:" + second.packageName).performClick();
                dialog = (android.app.AlertDialog) field(activity, "exportSizeDialog");
                dialog.dismiss();
                check(fixture.exportSizes.get(second.packageName) == 96, "Cancel preserves selected size");
                decor.findViewWithTag("export-size:" + second.packageName).performClick();
                dialog = (android.app.AlertDialog) field(activity, "exportSizeDialog");
                dialog.getListView().performItemClick(null, 0, 0);
                check(!fixture.exportSizes.containsKey(second.packageName), "Native choice removes explicit setting");
                fixture.exportSizes.put(second.packageName, 96);
                activity.removePack(0);
                check(!fixture.exportSizes.containsKey(second.packageName), "Removing pack clears its resolution");
                android.app.AlertDialog warning = activity.showExportResult(new ThemeEngine.Result(Uri.EMPTY,
                        1, "test.hnt", false, ExportSizes.WARNING_BYTES + 1));
                check(((android.widget.TextView) warning.findViewById(android.R.id.message)).getText().toString()
                        .contains(activity.getString(R.string.export_size_warning)), "Actual oversized result shows warning");
                warning.dismiss();
                android.app.AlertDialog normal = activity.showExportResult(new ThemeEngine.Result(Uri.EMPTY,
                        1, "test.hnt", false, ExportSizes.WARNING_BYTES));
                check(!((android.widget.TextView) normal.findViewById(android.R.id.message)).getText().toString()
                        .contains(activity.getString(R.string.export_size_warning)), "Exact threshold does not warn");
                normal.dismiss();
            });
            waitForThumbnails(activity);
        } finally { runOnMainChecked(activity::finish); }
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
                settings.packs.add(pack.packageName);
                settings.title = "My mixed theme";
                settings.tree = "content://test/tree";
                settings.shape = IconShape.SQUIRCLE;
                settings.fullPack = true;
                settings.clearFolderBackground = false;
                settings.hideVolte = true;
                settings.coverPath = coverFile.getName();
                settings.overrides.putAll(overrides);
                settings.overrides.put("dev.test.excluded/dev.test.excluded.Main", "");
                TemplateStore.Entry first = store.save(settings, pack.label, false);
                check(first.name().startsWith(pack.label + " · ") &&                 first.name().matches(".*\\d{2}:\\d{2}:\\d{2} [+-]\\d{2}:\\d{2}"),
                                        "Template name contains pack title and timestamp without milliseconds");
                check(store.list().size() == 1, "First successful save creates one template");
                check(!first.settings().has("icons") && first.settings().getJSONObject("overrides").getString(app.key()).equals(reference),
                        "Template stores references rather than icon images");
                settings.title = "Unsaved change";
                Files.write(coverFile.toPath(), new byte[]{1, 2, 3});
                Draft restored = store.open(first.id());
                check(restored.title.equals("My mixed theme") && !restored.toJson().has("filename")
                        && restored.packs.equals(List.of(pack.packageName)) && restored.tree.equals("content://test/tree")
                        && restored.shape == IconShape.SQUIRCLE && restored.fullPack && !restored.clearFolderBackground
                        && restored.hideVolte,
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
                legacy.put("filename", "Old manually chosen name.hnt");
                legacy.getJSONObject("overrides").put(app.key(), "google_phone");
                check(Draft.fromJson(legacy).overrides.get(app.key()).equals(pack.packageName + ":google_phone"),
                        "Old manual assignments migrate to explicit original-pack references");
                check(Draft.fromJson(legacy).title.equals(settings.title) && !Draft.fromJson(legacy).toJson().has("filename"),
                        "Legacy template filenames are ignored without changing title or other settings");
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
                        activity.getString(R.string.other_pack));
                check(button != null, "Icon picker initially offers current base pack");
                android.widget.Button automatic = (android.widget.Button) findText(dialog.getWindow().getDecorView(),
                        activity.getString(R.string.automatic));
                android.widget.Button originalButton = (android.widget.Button) findText(dialog.getWindow().getDecorView(),
                        activity.getString(R.string.keep_original));
                android.widget.Button fileButton = (android.widget.Button) findText(dialog.getWindow().getDecorView(),
                        activity.getString(R.string.choose_icon_file));
                check(fileButton != null && fileButton.getParent() == button.getParent(),
                        "Other pack and file buttons share one row");
                check(originalButton.getVisibility() == android.view.View.GONE
                                && automatic.getVisibility() == android.view.View.GONE,
                        "Keep-system and template reset hidden while the system icon is used without manual choice");
                check(findText(dialog.getWindow().getDecorView(), "Совпадений нет") == null
                        && !hasDigitsOnlyText(dialog.getWindow().getDecorView()), "Icon picker shows no result count");
                float density = activity.getResources().getDisplayMetrics().density;
                var left = (android.widget.LinearLayout.LayoutParams) button.getLayoutParams();
                var right = (android.widget.LinearLayout.LayoutParams) fileButton.getLayoutParams();
                var actionRow = (android.widget.LinearLayout.LayoutParams)
                        ((android.view.View) automatic.getParent()).getLayoutParams();
                check(left.rightMargin + right.leftMargin >= 12 * density - 1
                        && actionRow.topMargin >= 8 * density - 1 && actionRow.bottomMargin >= 12 * density - 1,
                        "Icon picker top actions have horizontal and vertical spacing");
                android.widget.EditText searchField =                 findEdit(dialog.getWindow().getDecorView());
                check(searchField != null && ((android.widget.LinearLayout.LayoutParams) searchField.getLayoutParams())
                                .topMargin >= 12 * density - 1,
                        "Search keeps spacing below source buttons when action row is hidden");
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
                check(findText(dialog.getWindow().getDecorView(), activity.getString(((IconRepository.Pack) field(activity, "pack")).members.size() > 1 ? R.string.template_packs : R.string.template_pack)) != null
                                && findText(dialog.getWindow().getDecorView(), activity.getString(R.string.other_pack)) != null,
                        "Another pack offers return to template pack and another choice");
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

    private android.widget.EditText findEdit(android.view.View view) {
        if (view instanceof android.widget.EditText edit) return edit;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText found = findEdit(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean hasDigitsOnlyText(android.view.View view) {
        if (view instanceof android.widget.TextView text && !(view instanceof android.widget.EditText)
                && view.getVisibility() == android.view.View.VISIBLE && text.getText().toString().matches("\\d+")) return true;
        if (view instanceof android.widget.GridView) return false;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) if (hasDigitsOnlyText(group.getChildAt(i))) return true;
        }
        return false;
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
        verifyFolderGlass(file);
    }

    private void verifyFolderGlass(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file);
             ZipInputStream icons = new ZipInputStream(zip.getInputStream(zip.getEntry("icons")))) {
            int configurations = 0;
            boolean hasFolderBackground = false;
            Map<String, String> flags = new LinkedHashMap<>();
            for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                String name = entry.getName();
                hasFolderBackground |= name.equals("portal_ring_inner_holo.png")
                        || name.equals("portal_ring_inner_holo_dark.png")
                        || name.equals("folder_path.svg") || name.startsWith("icon_folders/")
                        || name.startsWith("folder_paths/");
                if (!name.equals("transparent_config.xml")) continue;
                configurations++;
                org.xmlpull.v1.XmlPullParser xml = android.util.Xml.newPullParser();
                xml.setInput(new java.io.ByteArrayInputStream(icons.readAllBytes()), "UTF-8");
                boolean root = false;
                for (int event = xml.next(); event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; event = xml.next()) {
                    if (event != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                    if (xml.getName().equals("HnTheme")) root = true;
                    else {
                        String flag = xml.getName();
                        check((flag.equals("transparent") || flag.equals("highlight")) && !flags.containsKey(flag),
                                "Folder glass configuration has only distinct supported flags");
                        flags.put(flag, xml.nextText());
                    }
                }
                check(root, "Folder glass configuration uses the HONOR theme root");
            }
            check(configurations == 1 && flags.equals(Map.of("transparent", "false", "highlight", "true")),
                    "Icon theme enables folder glass without transparent icon mode");
            check(!hasFolderBackground, "Export does not replace the small folder background or path");
        }
    }

    private File replaceFolderGlass(Context context, File file, String replacement) throws Exception {
        File result = File.createTempFile("folder-glass-check-", ".hnt", context.getCacheDir());
        boolean complete = false, found = false;
        try {
            try (ZipFile original = new ZipFile(file);
                 ZipOutputStream output = new ZipOutputStream(new FileOutputStream(result))) {
                var entries = original.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    output.putNextEntry(new ZipEntry(entry.getName()));
                    if (entry.getName().equals("icons")) {
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                        try (ZipInputStream icons = new ZipInputStream(original.getInputStream(entry));
                             ZipOutputStream changed = new ZipOutputStream(bytes)) {
                            for (ZipEntry icon; (icon = icons.getNextEntry()) != null;) {
                                boolean config = icon.getName().equals("transparent_config.xml");
                                found |= config;
                                if (config && replacement == null) continue;
                                changed.putNextEntry(new ZipEntry(icon.getName()));
                                if (config) changed.write(replacement.getBytes(StandardCharsets.UTF_8));
                                else icons.transferTo(changed);
                                changed.closeEntry();
                            }
                        }
                        output.write(bytes.toByteArray());
                    } else {
                        try (var input = original.getInputStream(entry)) { input.transferTo(output); }
                    }
                    output.closeEntry();
                }
            }
            if (!found) throw new AssertionError("Original archive is missing folder glass metadata");
            complete = true;
            return result;
        } finally {
            if (!complete) Files.deleteIfExists(result.toPath());
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

    private void verifyFileIconUi(MainActivity activity, IconRepository.Pack pack) throws Exception {
        Draft draft = (Draft) field(activity, "draft");
        Map<String, String> originalOverrides = new LinkedHashMap<>(draft.overrides);
        @SuppressWarnings("unchecked")
        IconRepository.App app = ((List<IconRepository.App>) field(activity, "apps")).get(0);
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        File input = File.createTempFile("file-icon-ui-", ".png", activity.getCacheDir());
        Bitmap image = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        image.eraseColor(android.graphics.Color.CYAN);
        image.setPixel(96, 96, android.graphics.Color.rgb((int) (System.nanoTime() & 255), 21, 33));
        try (FileOutputStream output = new FileOutputStream(input)) { image.compress(Bitmap.CompressFormat.PNG, 100, output); }
        finally { image.recycle(); }
        String[] created = {null};
        android.content.IntentFilter pickerFilter = new android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT);
        pickerFilter.addDataType("image/*");
        pickerFilter.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        ActivityMonitor picker = addMonitor(pickerFilter, null, true);
        try {
            runOnMainSync(() -> {
                activity.showIcons(app, () -> changed.set(true));
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "iconDialog");
                findText(dialog.getWindow().getDecorView(), activity.getString(R.string.choose_icon_file)).performClick();
            });
            waitForIdleSync();
            check(picker.getHits() == 1, "File icon button launches standard document picker");
            runOnMainSync(() -> activity.onActivityResult(14, android.app.Activity.RESULT_OK,
                    new android.content.Intent().setData(Uri.fromFile(input))));
            waitForWork(activity);
            runOnMainSync(() -> {
                android.app.Dialog preview = (android.app.Dialog) field(activity, "fileIconDialog");
                check(preview != null && preview.isShowing(), "Selected file shown in preview before assignment");
                check(!changed.get() && draft.overrides.equals(originalOverrides),
                        "Preview does not assign icon before confirmation");
                preview.onBackPressed();
                check(!changed.get() && draft.overrides.equals(originalOverrides), "Cancelling preview preserves assignment");
            });
            runOnMainSync(() -> activity.onActivityResult(14, android.app.Activity.RESULT_OK,
                    new android.content.Intent().setData(Uri.fromFile(input))));
            waitForWork(activity);
            runOnMainSync(() -> {
                android.app.Dialog preview = (android.app.Dialog) field(activity, "fileIconDialog");
                findText(preview.getWindow().getDecorView(), activity.getString(R.string.use_icon_file)).performClick();
            });
            waitForWork(activity);
            check(changed.get() && FileIcons.isFile(draft.overrides.get(app.key())), "Confirming preview assigns persistent file reference");
            created[0] = draft.overrides.get(app.key());
            Bitmap actual = pack.render(created[0], 192);
            try { check(actual.getPixel(40, 40) == android.graphics.Color.CYAN, "Confirmed file icon visible through normal rendering"); }
            finally { actual.recycle(); }
            Files.writeString(input.toPath(), "broken image");
            runOnMainSync(() -> {
                activity.showIcons(app, () -> changed.set(true));
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "iconDialog");
                findText(dialog.getWindow().getDecorView(), activity.getString(R.string.choose_icon_file)).performClick();
                activity.onActivityResult(14, android.app.Activity.RESULT_OK,
                        new android.content.Intent().setData(Uri.fromFile(input)));
            });
            waitForWork(activity);
            runOnMainSync(() -> {
                check(created[0].equals(draft.overrides.get(app.key())), "Invalid file leaves confirmed icon unchanged");
                android.app.AlertDialog error = (android.app.AlertDialog) field(activity, "errorDialog");
                check(error != null && error.isShowing(), "Invalid file displays actionable error");
                error.dismiss();
            });
        } finally {
            removeMonitor(picker);
            runOnMainSync(() -> {
                draft.overrides.clear(); draft.overrides.putAll(originalOverrides);
                try { draft.save(activity); } catch (Exception error) { throw new AssertionError(error); }
                android.app.Dialog dialog = (android.app.Dialog) field(activity, "iconDialog");
                if (dialog != null) dialog.dismiss();
                dialog = (android.app.Dialog) field(activity, "fileIconDialog");
                if (dialog != null) dialog.dismiss();
            });
            if (created[0] != null && !originalOverrides.containsValue(created[0])) {
                Files.deleteIfExists(new File(FileIcons.directory(activity), IconRepository.resourceName(created[0]) + ".png").toPath());
            }
            Files.deleteIfExists(input.toPath());
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
                android.view.View remove = findText(activity.getWindow().getDecorView(), activity.getString(R.string.remove_wallpaper));
                check(remove.getVisibility() == android.view.View.VISIBLE, "Remove wallpaper action appears once wallpaper is added");
                remove.performClick();
                check(draft.wallpaperPath.isEmpty(), "Removing wallpaper explicitly clears draft setting");
                check(remove.getVisibility() == android.view.View.GONE, "Remove wallpaper action hidden without wallpaper");
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
            ThemeEngine.Result first = ThemeEngine.export(context, "Original", name, false, IconShape.NONE, null, false,
                    pack, List.of(app), overrides, cover, tree, null, null, null, false, true,
                    null, false, true, null, 100, List.of(), Map.of(pack.packageName, 96), (done, total) -> {});
            created = first.uri();
            check(ThemeEngine.findDocument(context.getContentResolver(), tree, name.toUpperCase(java.util.Locale.ROOT))
                    .equals(created), "Existing filename found case-insensitively through actual SAF provider");
            byte[] original;
            try (var input = context.getContentResolver().openInputStream(created)) { original = input.readAllBytes(); }
            check(first.bytes() == original.length && first.bytes() > 0, "Export reports exact persisted file size");
            File originalFile = File.createTempFile("saf-size-", ".hnt", context.getCacheDir());
            try {
                Files.write(originalFile.toPath(), original);
                ThemeEngine.verifyArchive(originalFile, 1, IconShape.NONE, null, false, 170,
                        false, null, null, null, 96);
            } finally { Files.delete(originalFile.toPath()); }
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
                check(second.bytes() == saved.length(), "Replacement also reports exact persisted file size");
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

    private IconRepository.Pack syntheticPack(IconRepository.Pack real, String packageName, String label) {
        IconRepository.Pack result = new IconRepository.Pack(packageName, label, real.resources, real.badge, real.manager);
        result.fileIcons = real.fileIcons;
        return result;
    }

    private void verifyPngEncoding(Context context) throws Exception {
        Bitmap fewColors = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[128 * 128];
        int[] colors = {Color.TRANSPARENT, Color.argb(64, 255, 0, 255),
                Color.argb(160, 255, 0, 255), Color.WHITE};
        for (int i = 0; i < pixels.length; i++) pixels[i] = colors[(i / 128 + i % 128) % colors.length];
        fewColors.setPixels(pixels, 0, 128, 0, 0, 128, 128);
        try {
            java.io.ByteArrayOutputStream original = new java.io.ByteArrayOutputStream();
            check(fewColors.compress(Bitmap.CompressFormat.PNG, 100, original), "Reference PNG encoding succeeds");
            byte[] encoded = ThemeEngine.png(fewColors);
            check(pngColorType(encoded) == 3 && encoded.length < original.size(),
                    "Small RGBA palette exports as a smaller indexed PNG");
            Bitmap decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
            try {
                check(decoded != null && decoded.sameAs(fewColors), "Indexed PNG preserves exact pixels and transparency");
                check(Color.alpha(decoded.getPixel(1, 0)) == 64 && Color.alpha(decoded.getPixel(2, 0)) == 160
                                && Color.alpha(decoded.getPixel(3, 0)) == 255,
                        "Indexed PNG preserves per-color alpha");
            } finally { if (decoded != null) decoded.recycle(); }
        } finally { fewColors.recycle(); }

        Bitmap allAlpha = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888);
        pixels = new int[256 * 128];
        int alphaState = 0x2468ace;
        for (int i = 0; i < pixels.length; i++) {
            alphaState = alphaState * 1664525 + 1013904223;
            int alpha = i < 256 ? i : alphaState >>> 24;
            pixels[i] = Color.argb(alpha, alpha * 73 & 255, alpha * 37 & 255, alpha * 101 & 255);
        }
        allAlpha.setPixels(pixels, 0, 256, 0, 0, 256, 128);
        try {
            java.io.ByteArrayOutputStream original = new java.io.ByteArrayOutputStream();
            check(allAlpha.compress(Bitmap.CompressFormat.PNG, 100, original), "All-alpha reference PNG encoding succeeds");
            byte[] encoded = ThemeEngine.png(allAlpha);
            check(pngColorType(encoded) == 3, "All 256 alpha values remain eligible for indexed PNG");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPremultiplied = false;
            Bitmap baseline = BitmapFactory.decodeByteArray(original.toByteArray(), 0, original.size(), options);
            Bitmap actual = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            try {
                String mismatch = baseline == null || actual == null ? "decode failed" : firstPixelMismatch(baseline, actual);
                check(mismatch == null, "Indexed PNG preserves the original encoder's exact unpremultiplied RGBA"
                        + (mismatch == null ? "" : ": " + mismatch));
            } finally {
                if (baseline != null) baseline.recycle();
                if (actual != null) actual.recycle();
            }
        } finally { allAlpha.recycle(); }

        Bitmap limit = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        pixels = new int[256 * 256];
        int state = 0x13579bdf;
        for (int i = 0; i < pixels.length; i++) {
            state = state * 1664525 + 1013904223;
            int color = i < 256 ? i : state >>> 24;
            pixels[i] = Color.rgb(color, color * 37 & 255, color * 101 & 255);
        }
        limit.setPixels(pixels, 0, 256, 0, 0, 256, 256);
        try {
            byte[] encoded = ThemeEngine.png(limit);
            check(pngColorType(encoded) == 3, "Exactly 256 colors remain eligible for indexed PNG");
            Bitmap decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
            try {
                String mismatch = decoded == null ? "decode failed" : firstPixelMismatch(limit, decoded);
                check(mismatch == null, "256-color PNG decodes pixel-for-pixel" + (mismatch == null ? "" : ": " + mismatch));
            }
            finally { if (decoded != null) decoded.recycle(); }
        } finally { limit.recycle(); }

        Bitmap overLimit = Bitmap.createBitmap(257, 32, Bitmap.Config.ARGB_8888);
        pixels = new int[257 * 32];
        for (int i = 0; i < pixels.length; i++) {
            int color = i % 257;
            pixels[i] = color == 256 ? Color.rgb(1, 2, 3)
                    : Color.rgb(color, color * 37 & 255, color * 101 & 255);
        }
        overLimit.setPixels(pixels, 0, 257, 0, 0, 257, 32);
        try {
            byte[] encoded = ThemeEngine.png(overLimit);
            check(pngColorType(encoded) == 6, "More than 256 colors use the lossless RGBA fallback");
            Bitmap decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
            try { check(decoded != null && decoded.sameAs(overLimit), "RGBA fallback decodes pixel-for-pixel"); }
            finally { if (decoded != null) decoded.recycle(); }
        } finally { overLimit.recycle(); }

        Bitmap stripes = Bitmap.createBitmap(256, 8, Bitmap.Config.ARGB_8888);
        pixels = new int[256 * 8];
        for (int i = 0; i < pixels.length; i++) {
            int color = i & 255;
            pixels[i] = Color.rgb(color, color * 37 & 255, color * 101 & 255);
        }
        stripes.setPixels(pixels, 0, 256, 0, 0, 256, 8);
        try {
            java.io.ByteArrayOutputStream original = new java.io.ByteArrayOutputStream();
            check(stripes.compress(Bitmap.CompressFormat.PNG, 100, original), "Striped reference PNG encoding succeeds");
            check(java.util.Arrays.equals(ThemeEngine.png(stripes), original.toByteArray()),
                    "Unprofitable palette preserves the original PNG bytes");
        } finally { stripes.recycle(); }

        IconRepository.Pack pack = new IconRepository.Pack(context.getPackageName(), "PNG test",
                context.getResources(), null, context.getPackageManager());
        pack.icons.put("png_test", new IconRepository.Icon("png_test", "test", R.drawable.app_icon));
        IconRepository.App app = new IconRepository.App(
                new ComponentName("dev.iconmanager.pngtest", "dev.iconmanager.pngtest.Main"), "PNG test");
        Map<String, String> overrides = Map.of(app.key(), "png_test");
        Bitmap cover = ThemeEngine.cover(pack, List.of(app), overrides);
        ThemeEngine.Built built;
        try {
            built = ThemeEngine.build(context, "PNG encoding check", false, IconShape.NONE, false,
                    pack, List.of(app), overrides, cover, (done, total) -> {});
        } finally { cover.recycle(); }
        try {
            ThemeEngine.verifyArchive(built.file(), 1, IconShape.NONE, false);
            try (ZipFile archive = new ZipFile(built.file());
                 ZipInputStream icons = new ZipInputStream(archive.getInputStream(archive.getEntry("icons")))) {
                ZipEntry entry = icons.getNextEntry();
                check(entry != null && entry.getName().equals("dev.iconmanager.pngtest.png"),
                        "Optimized PNG is included at the expected archive path");
                byte[] encoded = icons.readAllBytes();
                Bitmap actual = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
                Bitmap expected = pack.render("png_test", pack.iconSize());
                try {
                    String mismatch = actual == null ? "decode failed" : firstPixelMismatch(expected, actual);
                    check(mismatch == null, "Validated HNT archive preserves the rendered icon pixels"
                            + (mismatch == null ? "" : ": " + mismatch));
                } finally {
                    if (actual != null) actual.recycle();
                    expected.recycle();
                }
            }
        } finally { Files.deleteIfExists(built.file().toPath()); }
    }

    private int pngColorType(byte[] png) {
        check(png.length >= 26 && png[0] == (byte) 137 && png[1] == 80 && png[2] == 78
                        && png[3] == 71 && png[4] == 13 && png[5] == 10 && png[6] == 26 && png[7] == 10
                        && png[12] == 73 && png[13] == 72 && png[14] == 68 && png[15] == 82,
                "PNG signature and IHDR are present");
        return png[25] & 255;
    }

    private void verifyPackPngEncoding(Context context, String packageName) throws Exception {
        IconRepository.Pack pack = new IconRepository(context).load(packageName);
        List<String> names = pack.mappings.values().stream().distinct().sorted().limit(3).toList();
        check(!names.isEmpty(), "PNG measurement pack has direct appfilter mappings");
        List<IconRepository.App> apps = new ArrayList<>();
        Map<String, String> overrides = new LinkedHashMap<>();
        Map<String, byte[]> expectedPngs = new LinkedHashMap<>();
        int size = pack.iconSize();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            IconRepository.App app = new IconRepository.App(new ComponentName("dev.iconmanager.pngtest" + i,
                    "dev.iconmanager.pngtest" + i + ".Main"), name);
            apps.add(app);
            overrides.put(app.key(), name);
            byte[] source;
            try (var input = pack.resources.openRawResource(pack.icons.get(name).id())) {
                source = input.readAllBytes();
            }
            Bitmap rendered = pack.render(name, size);
            byte[] encoded;
            java.io.ByteArrayOutputStream original = new java.io.ByteArrayOutputStream();
            try {
                check(rendered.compress(Bitmap.CompressFormat.PNG, 100, original), "Pack reference PNG encoding succeeds");
                encoded = ThemeEngine.png(rendered);
            } finally { rendered.recycle(); }
            check(encoded.length <= original.size(), "Pack PNG never exceeds the original encoded size");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPremultiplied = false;
            Bitmap baseline = BitmapFactory.decodeByteArray(original.toByteArray(), 0, original.size(), options);
            Bitmap actual = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            try {
                String mismatch = baseline == null || actual == null ? "decode failed" : firstPixelMismatch(baseline, actual);
                check(mismatch == null, "Pack PNG preserves exact unpremultiplied RGBA: " + name
                        + (mismatch == null ? "" : ": " + mismatch));
                check(actual.getWidth() == size && actual.getHeight() == size, "Pack PNG keeps export dimensions");
            } finally {
                if (baseline != null) baseline.recycle();
                if (actual != null) actual.recycle();
            }
            expectedPngs.put(app.component().getPackageName() + ".png", encoded);
            Bundle status = new Bundle();
            status.putString("stream", "PNG " + packageName + "/" + name + " " + size + "x" + size
                    + ": source=" + source.length + " B, original=" + original.size() + " B, optimized="
                    + encoded.length + " B, colorType=" + pngColorType(encoded) + "\n");
            sendStatus(0, status);
        }
        Bitmap cover = ThemeEngine.cover(pack, apps, overrides);
        ThemeEngine.Built built;
        try {
            built = ThemeEngine.build(context, "Pack PNG encoding check", false, IconShape.NONE, false,
                    pack, apps, overrides, cover, (done, total) -> {});
        } finally { cover.recycle(); }
        try {
            ThemeEngine.verifyArchive(built.file(), names.size(), IconShape.NONE, false);
            try (ZipFile archive = new ZipFile(built.file());
                 ZipInputStream icons = new ZipInputStream(archive.getInputStream(archive.getEntry("icons")))) {
                int count = 0;
                for (ZipEntry entry; (entry = icons.getNextEntry()) != null;) {
                    if (!entry.getName().endsWith(".png")) continue;
                    check(java.util.Arrays.equals(expectedPngs.get(entry.getName()), icons.readAllBytes()),
                            "Validated HNT includes the measured PNG unchanged: " + entry.getName());
                    count++;
                }
                check(count == names.size(), "Measured pack HNT contains every expected PNG");
            }
        } finally { Files.delete(built.file().toPath()); }
    }

    private String firstPixelMismatch(Bitmap expected, Bitmap actual) {
        int width = expected.getWidth(), height = expected.getHeight();
        if (actual.getWidth() != width || actual.getHeight() != height) return "dimensions differ";
        int[] expectedPixels = new int[width * height], actualPixels = new int[width * height];
        expected.getPixels(expectedPixels, 0, width, 0, 0, width, height);
        actual.getPixels(actualPixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < expectedPixels.length; i++) {
            if (expectedPixels[i] != actualPixels[i]) {
                return (i % width) + "," + (i / width) + " expected "
                        + Integer.toHexString(expectedPixels[i]) + " got " + Integer.toHexString(actualPixels[i]);
            }
        }
        return null;
    }

    private void syntheticMapping(IconRepository.Pack target, IconRepository.Pack real, String component, String icon) {
        ComponentName name = ComponentName.unflattenFromString(component);
        target.icons.put(icon, real.icons.get(icon));
        target.mappings.put(name.flattenToString(), icon);
        target.byPackage.putIfAbsent(name.getPackageName(), icon);
    }

    private void verifyMultiPack(IconRepository.Pack real) throws Exception {
        List<String> names = real.icons.keySet().stream().sorted().limit(200).toList();
        IconRepository.Pack one = syntheticPack(real, "dev.pack.one", "One");
        IconRepository.Pack two = syntheticPack(real, "dev.pack.two", "Two");
        IconRepository.Pack five = syntheticPack(real, "dev.pack.five", "Five");
        syntheticMapping(one, real, "dev.app.a/dev.app.a.Main", names.get(0));
        syntheticMapping(two, real, "dev.app.a/dev.app.a.Main", names.get(1));
        syntheticMapping(two, real, "dev.app.c/dev.app.c.Main", names.get(2));
        syntheticMapping(five, real, "dev.app.a/dev.app.a.Main", names.get(3));
        syntheticMapping(five, real, "dev.app.b/dev.app.b.Main", names.get(4));
        syntheticMapping(five, real, "dev.app.c/dev.app.c.Main", names.get(5));
        IconRepository.App a = new IconRepository.App(new ComponentName("dev.app.a", "dev.app.a.Main"), "A");
        IconRepository.App b = new IconRepository.App(new ComponentName("dev.app.b", "dev.app.b.Main"), "B");
        IconRepository.App c = new IconRepository.App(new ComponentName("dev.app.c", "dev.app.c.Main"), "C");
        IconRepository.App none = new IconRepository.App(new ComponentName("dev.app.none", "dev.app.none.Main"), "None");
        check(IconRepository.combine(List.of(real)) == real, "Single base pack is used unchanged");
        check(IconRepository.combine(List.of()) == null, "No base packs produce no combined pack");
        IconRepository.Pack combined = IconRepository.combine(List.of(one, two, five));
        check(("dev.pack.one:" + names.get(0)).equals(combined.automatic(a)), "App present in packs 1, 2 and 5 uses pack 1");
        check(("dev.pack.five:" + names.get(4)).equals(combined.automatic(b)), "App missing from higher packs falls back to pack 5");
        check(("dev.pack.two:" + names.get(2)).equals(combined.automatic(c)), "App present in packs 2 and 5 uses pack 2");
        check(combined.automatic(none) == null, "App absent from every pack stays unthemed");
        check(combined.labels().equals(List.of("One", "Two", "Five"))
                        && combined.packageNames().equals(List.of("dev.pack.one", "dev.pack.two", "dev.pack.five")),
                "Combined pack keeps priority order");
        Map<String, String> installed = ThemeEngine.exportTargets(List.of(a, b, c, none), combined, Map.of(), false);
        check(installed.equals(Map.of("dev.app.a", "dev.pack.one:" + names.get(0), "dev.app.b", "dev.pack.five:" + names.get(4),
                "dev.app.c", "dev.pack.two:" + names.get(2))), "Installed-only export resolves each app by pack priority");
        ThemeEngine.ExportPlan plan = ThemeEngine.exportPlan(List.of(a, b, c, none), combined, Map.of(), false);
        check(plan.targets().equals(installed) && plan.count() == 3,
                "Combined export reuses pack selection results for targets and count");
        Map<String, String> manual = Map.of(a.key(), "dev.pack.five:" + names.get(3));
        check(("dev.pack.five:" + names.get(3)).equals(combined.selected(a, manual)), "Manual choice overrides pack priority");
        Bitmap expected = real.render(names.get(4), 64), actual = combined.render(combined.automatic(b), 64);
        try { check(expected.sameAs(actual), "Combined pack renders icons from their source pack"); }
        finally { expected.recycle(); actual.recycle(); }
        IconRepository.Pack first = syntheticPack(real, "dev.pack.first", "First");
        IconRepository.Pack disjoint = syntheticPack(real, "dev.pack.disjoint", "Disjoint");
        IconRepository.Pack same = syntheticPack(real, "dev.pack.same", "Same");
        for (int i = 0; i < 100; i++) {
            syntheticMapping(first, real, "dev.shared.app" + i + "/.Main", names.get(i));
            syntheticMapping(disjoint, real, "dev.other.app" + i + "/.Main", names.get(100 + i));
            syntheticMapping(same, real, "dev.shared.app" + i + "/.Main", names.get(100 + i));
        }
        IconRepository.Pack union = IconRepository.combine(List.of(first, disjoint));
        ThemeEngine.ExportPlan unionPlan = ThemeEngine.exportPlan(List.of(), union, Map.of(), true);
        check(ThemeEngine.exportCount(List.of(), union, Map.of(), true) == 200
                        && ThemeEngine.exportTargets(List.of(), union, Map.of(), true).size() == 200
                        && unionPlan.count() == 200 && unionPlan.targets().size() == 200,
                "Whole-pack export of two disjoint 100-icon packs contains 200 icons");
        IconRepository.Pack overlap = IconRepository.combine(List.of(first, same));
        Map<String, String> overlapTargets = ThemeEngine.exportTargets(List.of(), overlap, Map.of(), true);
        check(ThemeEngine.exportCount(List.of(), overlap, Map.of(), true) == 100 && overlapTargets.size() == 100
                        && overlapTargets.values().stream().allMatch(value -> value.startsWith("dev.pack.first:")),
                "Whole-pack export of identical packs keeps only 100 icons from the first pack");
        Map<String, String> reversed = ThemeEngine.exportTargets(List.of(), IconRepository.combine(List.of(same, first)), Map.of(), true);
        check(reversed.size() == 100 && reversed.values().stream().allMatch(value -> value.startsWith("dev.pack.same:")),
                "Reordering packs changes which duplicate wins");
        check(ThemeEngine.credits(List.of("A")).equals("На основе иконпака «A». Права на иконки принадлежат разработчику иконпака. "
                        + "Только для персонального использования.")
                        && ThemeEngine.credits(List.of("A", "B")).equals("На основе иконпаков «A», «B». Права на иконки "
                        + "принадлежат разработчикам иконпаков. Только для персонального использования."),
                "Theme description credits every base pack");
        Draft draft = new Draft();
        draft.packs.addAll(List.of("dev.pack.two", "dev.pack.one"));
        org.json.JSONObject json = draft.toJson();
        check(json.getString("pack").equals("dev.pack.two") && json.getJSONArray("packs").length() == 2
                        && Draft.fromJson(json).packs.equals(List.of("dev.pack.two", "dev.pack.one")),
                "Draft persists pack priority order");
        draft.packs.remove(1);
        check(!draft.toJson().has("packs") && Draft.fromJson(draft.toJson()).packs.equals(List.of("dev.pack.two")),
                "Single-pack drafts keep the previous template format");
        check(Draft.fromJson(new org.json.JSONObject().put("pack", "legacy.pack")).packs.equals(List.of("legacy.pack")),
                "Legacy single-pack templates migrate to the packs list");
        check(Draft.fromJson(new org.json.JSONObject().put("packs", new org.json.JSONArray().put("x.a").put("x.b").put("x.a")))
                .packs.equals(List.of("x.a", "x.b")), "Duplicate base packs are ignored");
        org.json.JSONArray tooMany = new org.json.JSONArray();
        for (int i = 0; i <= IconRepository.MAX_PACKS; i++) tooMany.put("dev.pack.n" + i);
        boolean rejected = false;
        try { Draft.fromJson(new org.json.JSONObject().put("packs", tooMany)); }
        catch (org.json.JSONException error) { rejected = true; }
        check(rejected, "No more than ten base packs are accepted");
    }

    private void onMain(Runnable action) {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] instanceof RuntimeException runtime) throw runtime;
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private void verifyMultiPackUi(MainActivity activity, IconRepository.Pack pack) throws Exception {
        IconRepository.Pack other = syntheticPack(pack, "dev.pack.second", "Second");
        syntheticMapping(other, pack, "dev.app.second/.Main", pack.icons.keySet().iterator().next());
        Draft draft = (Draft) field(activity, "draft");
        List<String> original = List.copyOf(draft.packs);
        int n = original.size();
        List<String> added = new ArrayList<>(original); added.add("dev.pack.second");
        List<String> raised = new ArrayList<>(original); raised.add(n - 1, "dev.pack.second");
        try {
            onMain(() -> {
                check(n >= 1 && n < IconRepository.MAX_PACKS && original.contains(pack.packageName),
                        "Editor starts with the user's base packs");
                activity.selectPack(other);
                android.widget.LinearLayout list = (android.widget.LinearLayout) field(activity, "packList");
                check(draft.packs.equals(added) && list.getChildCount() == n + 1, "Added pack is appended with lowest priority");
                IconRepository.Pack combined = (IconRepository.Pack) field(activity, "pack");
                check(combined.packageNames().equals(draft.packs), "Editor uses the combined pack in list order");
                check(((android.view.ViewGroup) list.getChildAt(0)).getChildAt(0).getVisibility() == android.view.View.VISIBLE,
                        "Drag handles appear for several packs");
                check(((android.widget.TextView) field(activity, "packInfo")).getVisibility() == android.view.View.GONE
                                && activity.getWindow().getDecorView().findViewWithTag("help-pack_priority_help") != null,
                        "Priority rule is available from the pack help button");
                activity.selectPack(other);
                check(draft.packs.size() == n + 1, "The same pack cannot be added twice");
                IconRepository.App app = new IconRepository.App(new ComponentName("dev.app.second", "dev.app.second.Main"), "Second");
                activity.showIcons(app, () -> {});
                android.app.Dialog icons = (android.app.Dialog) field(activity, "iconDialog");
                android.view.View decor = icons.getWindow().getDecorView();
                android.widget.GridView grid = findGrid(decor);
                android.widget.ScrollView railScroll = (android.widget.ScrollView) ((android.view.ViewGroup) grid.getParent()).getChildAt(0);
                android.widget.LinearLayout rail = (android.widget.LinearLayout) railScroll.getChildAt(0);
                check(railScroll.getVisibility() == android.view.View.VISIBLE && rail.getChildCount() == n + 1
                                && "Second".contentEquals(rail.getChildAt(n).getContentDescription()) && rail.getChildAt(n).isSelected()
                                && rail.getChildAt(n) instanceof android.widget.ImageView,
                        "Picker shows icon-only pack tabs and opens the pack supplying the app");
                check(grid.getAdapter().getCount() == 1, "Selected tab shows only its pack");
                check(findText(decor, activity.getString(R.string.other_pack)) != null, "Picker offers another icon pack");
                rail.getChildAt(0).performClick();
                check(grid.getAdapter().getCount() == combined.members.get(0).icons.size() && rail.getChildAt(0).isSelected()
                                && !rail.getChildAt(n).isSelected(), "Tapping a tab switches the pack");
                android.widget.EditText search = findEdit(decor);
                search.setText(other.icons.keySet().iterator().next());
                boolean second = false, others = false;
                for (int i = 0; i < grid.getAdapter().getCount(); i++) {
                    String owner = ((MainActivity.IconChoice) grid.getAdapter().getItem(i)).source().packageName;
                    if (owner.equals("dev.pack.second")) second = true; else others = true;
                }
                check(second && others, "Search covers every template pack at once");
                icons.dismiss();
            });
            waitForIdleSync();
            onMain(() -> {
                android.widget.LinearLayout list = (android.widget.LinearLayout) field(activity, "packList");
                android.view.View row = list.getChildAt(n);
                android.view.View handle = ((android.view.ViewGroup) row).getChildAt(0);
                float distance = row.getTop() - list.getChildAt(n - 1).getTop();
                check(distance > 0, "Pack rows are laid out before dragging");
                long time = android.os.SystemClock.uptimeMillis();
                for (int[] step : new int[][]{{android.view.MotionEvent.ACTION_DOWN, 0},
                        {android.view.MotionEvent.ACTION_MOVE, -1}, {android.view.MotionEvent.ACTION_UP, -1}}) {
                    android.view.MotionEvent event = android.view.MotionEvent.obtain(time, time, step[0], 10, 200 + step[1] * distance * 0.75f, 0);
                    handle.dispatchTouchEvent(event);
                    event.recycle();
                }
                check(draft.packs.equals(raised), "Dragging a pack up raises its priority");
                android.widget.LinearLayout moved = (android.widget.LinearLayout) field(activity, "packList");
                check(moved.getChildAt(n - 1).performAccessibilityAction(R.id.pack_move_down, null) && draft.packs.equals(added),
                        "Priority can also be changed with accessibility actions");
                try {
                    check(Draft.load(activity).packs.equals(draft.packs), "Pack order is saved in the draft");
                } catch (org.json.JSONException error) { throw new AssertionError(error); }
                ((android.widget.ImageButton) ((android.view.ViewGroup) moved.getChildAt(n)).getChildAt(3)).performClick();
                IconRepository.Pack restored = (IconRepository.Pack) field(activity, "pack");
                check(draft.packs.equals(original) && (n == 1 ? restored.members.isEmpty() : restored.packageNames().equals(original))
                                && restored.packageName.equals(original.get(0)),
                        "Removing the added pack restores the previous packs");
                if (n == 1) check(((android.widget.TextView) field(activity, "packInfo")).getVisibility() == android.view.View.GONE
                                && ((android.view.ViewGroup) ((android.widget.LinearLayout) field(activity, "packList"))
                                .getChildAt(0)).getChildAt(0).getVisibility() == android.view.View.GONE,
                        "A single pack shows no priority controls");
            });
        } finally {
            onMain(() -> {
                for (int i = draft.packs.size() - 1; i >= 0; i--) if (!original.contains(draft.packs.get(i))) activity.removePack(i);
                try { activity.getSharedPreferences("draft", 0).edit().putString("json", draft.toJson().toString()).commit(); }
                catch (org.json.JSONException error) { throw new AssertionError(error); }
            });
        }
    }

    private void verifyThemeEditing(MainActivity activity, IconRepository.Pack pack) throws Exception {
        runOnMainSync(() -> {
            android.widget.EditText title = (android.widget.EditText) field(activity, "title");
            String savedTitle = title.getText().toString();
            Draft editingDraft = (Draft) field(activity, "draft");
            String savedTree = editingDraft.tree;
            android.widget.Button export = (android.widget.Button) field(activity, "exportButton");
            title.setText("Button check");
            check(export.isEnabled(), "Export enabled with loaded pack, title and granted tree, without manual filename");
            IconRepository.Pack loadedPack = (IconRepository.Pack) field(activity, "pack");
            setField(activity, "pack", null);
            title.setText("Button check");
            check(!export.isEnabled(), "Export disabled without loaded icon pack");
            setField(activity, "pack", loadedPack);
            android.widget.ImageView styleExample = (android.widget.ImageView) field(activity, "stylePreview");
            check(activity.getString(R.string.style_preview).contentEquals(styleExample.getContentDescription())
                            && styleExample.getColorFilter() == null,
                    "Universal style preview is available without tinting its ImageView");
            title.setText(" ");
            check(!export.isEnabled(), "Export disabled for whitespace title");
            title.setText("Button check");
            editingDraft.tree = "";
            title.setText("Button check");
            check(!export.isEnabled(), "Export disabled without destination");
            editingDraft.tree = savedTree;
            title.setText("Custom / name: icons");
            check(export.isEnabled(), "Characters sanitized in auto filename do not restrict the display title");
            title.setText(savedTitle);
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
                check(titleLabel != null && titleLabel != title && titleLabel.getLabelFor() == title.getId(),
                        "Theme title retains a permanent associated label when filled");
                android.widget.LinearLayout identity = (android.widget.LinearLayout) title.getParent();
                check(identity.getChildCount() == 2 && identity.findViewWithTag("help-filename_help") != null
                                && findText(identity, activity.getString(R.string.filename_help)) == null,
                        "Automatic naming explanation moved to the title help button");
                check(((android.widget.LinearLayout.LayoutParams) identity.getChildAt(0).getLayoutParams()).bottomMargin
                                >= 8 * density - 1,
                        "Theme title help row keeps spacing before the text field");
                android.widget.LinearLayout body = (android.widget.LinearLayout) title.getParent().getParent();
                check(body.getChildAt(1) == identity
                                && findText(body.getChildAt(2), activity.getString(R.string.add_pack)) != null,
                        "Theme name precedes pack selection on the Basics tab");
                android.widget.LinearLayout editorRoot = (android.widget.LinearLayout) field(activity, "root");
                @SuppressWarnings("unchecked")
                List<android.view.View> pages = (List<android.view.View>) field(activity, "editorPages");
                @SuppressWarnings("unchecked")
                List<android.widget.Button> tabs = (List<android.widget.Button>) field(activity, "editorTabs");
                check(pages.size() == 4 && tabs.size() == 4, "Editor has four pages and matching bottom tabs");
                for (int i = 0; i < tabs.size(); i++) {
                    tabs.get(i).performClick();
                    for (int page = 0; page < pages.size(); page++) {
                        check(pages.get(page).isShown() == (page == i) && tabs.get(page).isSelected() == (page == i),
                                "Bottom tab selects exactly one editor page: " + i);
                    }
                }
                int[] helps = {R.string.intro, R.string.pack_priority_help, R.string.filename_help, R.string.wallpaper_help,
                        R.string.background_help, R.string.overlay_help, R.string.scope_help, R.string.fallback_shape_help,
                        R.string.folder_background_help, R.string.apps_help, R.string.destination_help};
                int[] helpTabs = {0, 0, 0, 0, 1, 1, 3, 1, 1, 2, 3};
                int[] styleHelpTabs = {-1, -1, -1, -1, 0, 1, -1, 2, 2, -1, -1};
                for (int i = 0; i < helps.length; i++) {
                    int help = helps[i];
                    tabs.get(helpTabs[i]).performClick();
                    if (styleHelpTabs[i] >= 0) editorRoot.findViewWithTag("style-tab-" + styleHelpTabs[i]).performClick();
                    android.view.View button = editorRoot.findViewWithTag(
                            "help-" + activity.getResources().getResourceEntryName(help));
                    check(button != null && button.isShown() && button.getLayoutParams().width >= 48 * density - 1
                                    && findText(editorRoot, activity.getString(help)) == null,
                            "Editor block has its own help button instead of small inline text: "
                                    + activity.getResources().getResourceEntryName(help));
                    android.widget.ImageButton helpButton = (android.widget.ImageButton) button;
                    check(helpButton.getImageTintList().getDefaultColor() == (int) field(activity, "MUTED")
                                    && helpButton.getPaddingLeft() >= 14 * density - 1
                                    && helpButton.getPaddingRight() >= 14 * density - 1,
                            "Help icon is smaller and muted while retaining a 48dp touch target");
                }
                editorRoot.findViewWithTag("help-scope_help").performClick();
                android.app.Dialog helpDialog = (android.app.Dialog) field(activity, "helpDialog");
                check(helpDialog != null && helpDialog.isShowing()
                                && findText(helpDialog.getWindow().getDecorView(), activity.getString(R.string.scope_help)) != null,
                        "Editor help button opens its explanation");
                helpDialog.dismiss();
            } finally { title.setText(savedTitle); }
        });
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
                check(progressCount(activity.getWindow().getDecorView()) == 0,
                        "Editor footer never shows duplicate progress indicator");
                android.widget.TextView footer = (android.widget.TextView) field(activity, "status");
                check(activity.getString(R.string.ready).contentEquals(footer.getText())
                                && (footer.getGravity() & android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
                                        == android.view.Gravity.CENTER_HORIZONTAL,
                        "Draft message remains centered and unchanged during work");
                check(!succeeded.get(), "No premature success while save is unfinished");
                android.app.Dialog popup = (android.app.Dialog) field(activity, "progressDialog");
                check(popup != null && popup.isShowing(), "Background work shows central progress popup");
                check(progressCount(popup.getWindow().getDecorView()) == 1, "Only popup contains a loading spinner");
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
            check(progressCount(activity.getWindow().getDecorView()) == 0
                            && activity.getString(R.string.ready)
                                    .contentEquals(((android.widget.TextView) field(activity, "status")).getText()),
                    "Draft footer is unchanged after operation completes");
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
        StringBuilder stacks = new StringBuilder();
        for (var entry : Thread.getAllStackTraces().entrySet()) {
            StackTraceElement[] trace = entry.getValue();
            if (java.util.Arrays.stream(trace).noneMatch(frame -> frame.getClassName().startsWith("dev.iconmanager.")
                    && !frame.getClassName().contains("DeviceTests"))) continue;
            stacks.append("\n--- ").append(entry.getKey().getName());
            for (int i = 0; i < Math.min(25, trace.length); i++) stacks.append("\n  at ").append(trace[i]);
        }
        throw new AssertionError("UI work did not finish in 60 seconds" + stacks);
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
