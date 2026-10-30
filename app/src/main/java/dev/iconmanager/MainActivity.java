package dev.iconmanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int COVER = 11, DIRECTORY = 12, WALLPAPER = 13, ICON_FILE = 14, OVERLAY_FILE = 15;
    private int BG, PANEL, MUTED, CONTROL, INK, ACCENT_TEXT;
    private static final int ACCENT = Color.rgb(255, 193, 7);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> images = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private Draft draft;
    private IconRepository repository;
    private IconRepository.Pack pack;
    private final List<IconRepository.Pack> packs = new ArrayList<>();
    private LinearLayout packList;
    private Button addPackButton;
    private List<IconRepository.App> apps = List.of();
    private LinearLayout root;
    private EditText title;
    private Button exportButton;
    private TextView packInfo, appInfo, destinationInfo, status, templateHelp;
    private ImageView coverPreview, wallpaperPreview;
    private Button removeWallpaper, discardCover, destinationButton;
    private TextView autoCoverInfo;
    private Dialog packDialog;
    private Dialog cropDialog;
    private Dialog iconDialog;
    private AlertDialog exportDialog, errorDialog, deleteDialog;
    private Dialog progressDialog;
    private TextView progressMessage;
    private String pendingIconApp;
    private Runnable pendingIconChanged;
    private Dialog fileIconDialog;
    private Dialog settingsDialog, overlayDialog, shapeDialog;
    private RadioGroup exportScope;
    private ImageView shapePreview;
    private Button shapeButton;
    private Button removeOverlay;
    private RadioGroup overlayScope;
    private OptionalSection coverSection, wallpaperSection, overlaySection;
    private boolean busy;
    private boolean editing;
    private TemplateStore templates;
    private CheckBox saveAsNew;
    private record SavedExport(ThemeEngine.Result theme, TemplateStore.Entry template, String title) {}
    private final java.util.Set<String> reportedImageErrors = new java.util.HashSet<>();

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(App.themed(base));
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        BG = dark ? Color.BLACK : Color.rgb(245, 245, 245);
        PANEL = dark ? Color.rgb(24, 24, 24) : Color.WHITE;
        CONTROL = dark ? Color.rgb(36, 36, 36) : Color.rgb(229, 229, 229);
        MUTED = dark ? Color.rgb(189, 189, 189) : Color.rgb(95, 95, 95);
        INK = dark ? Color.WHITE : Color.rgb(32, 32, 32);
        ACCENT_TEXT = dark ? ACCENT : Color.rgb(112, 78, 0);
        if (savedInstanceState != null) pendingIconApp = savedInstanceState.getString("pendingIconApp");
        repository = new IconRepository(this);
        templates = new TemplateStore(this);
        Exception draftError = null;
        try { draft = Draft.load(this); }
        catch (org.json.JSONException e) { draft = new Draft(); draftError = e; }
        if (savedInstanceState != null && savedInstanceState.getBoolean("editing")) openEditor();
        else showTemplates();
        if (draftError != null) showError(draftError);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (busy) showError(new IOException(getString(R.string.busy)));
            else if (editing) { saveDraft(); showTemplates(); }
            else finish();
        });
    }

    private void showSettings() {
        Dialog dialog = fullDialog();
        settingsDialog = dialog;
        LinearLayout layout = dialogLayout(dialog, getString(R.string.settings));
        LinearLayout header = (LinearLayout) layout.getChildAt(0);
        android.widget.ImageButton back = iconButton(R.drawable.ic_back, R.string.back, view -> dialog.dismiss());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        backParams.setMarginEnd(dp(12));
        header.addView(back, 0, backParams);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout content = column();
        scroll.addView(content);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout appearance = card(content, R.string.appearance);
        LinearLayout themeRow = row();
        appearance.addView(themeRow);
        String[] themes = {"system", "dark", "light"};
        int[] labels = {R.string.theme_system, R.string.theme_dark, R.string.theme_light};
        int[] icons = {R.drawable.ic_theme_system, R.drawable.ic_theme_dark, R.drawable.ic_theme_light};
        String selected = getSharedPreferences("settings", 0).getString("theme", "system");
        String[] chosenTheme = {selected};
        List<View> themeChoices = new ArrayList<>();
        for (int i = 0; i < themes.length; i++) {
            String value = themes[i];
            LinearLayout option = column();
            option.setTag(value);
            option.setGravity(Gravity.CENTER);
            option.setPadding(dp(6), dp(16), dp(6), dp(12));
            option.setMinimumHeight(dp(124));
            ImageView image = new ImageView(this);
            image.setImageResource(icons[i]);
            image.setImageTintList(ColorStateList.valueOf(INK));
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            option.addView(image, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView label = text(getString(labels[i]), 14, INK);
            label.setGravity(Gravity.CENTER);
            label.setMinHeight(dp(48));
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            option.addView(label, new LinearLayout.LayoutParams(-1, -2));
            View.OnClickListener choose = view -> { chosenTheme[0] = value; selectSetting(themeChoices, value); };
            option.setOnClickListener(choose);
            label.setOnClickListener(choose);
            option.setFocusable(true);
            option.setContentDescription(getString(labels[i]));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) params.setMarginStart(dp(8));
            themeRow.addView(option, params);
            themeChoices.add(option);
        }
        selectSetting(themeChoices, chosenTheme[0]);
        LinearLayout language = card(content, R.string.language);
        String current = getSystemService(android.app.LocaleManager.class).getApplicationLocales().toLanguageTags();
        String[] chosenLanguage = {current.startsWith("ru") ? "ru" : "en"};
        List<View> languageChoices = new ArrayList<>();
        for (String code : new String[]{"en", "ru"}) {
            String name = code.equals("ru") ? "Русский" : "English";
            LinearLayout option = row();
            option.setTag(code);
            option.setPadding(dp(16), dp(12), dp(16), dp(12));
            option.setMinimumHeight(dp(72));
            TextView badge = text(code.toUpperCase(Locale.ROOT), 13, MUTED);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(rounded(PANEL, 12));
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            option.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView label = text(name, 16, INK);
            label.setPadding(dp(16), 0, dp(12), 0);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            option.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(ColorStateList.valueOf(ACCENT_TEXT));
            check.setTag("selection");
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            option.addView(check, new LinearLayout.LayoutParams(dp(24), dp(24)));
            View.OnClickListener choose = view -> { chosenLanguage[0] = code; selectSetting(languageChoices, code); };
            option.setOnClickListener(choose); label.setOnClickListener(choose);
            option.setFocusable(true);
            option.setContentDescription(name);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(8);
            language.addView(option, params);
            languageChoices.add(option);
        }
        selectSetting(languageChoices, chosenLanguage[0]);
        Button apply = button(R.string.apply_settings, view -> {
            if (!getSharedPreferences("settings", 0).edit().putString("theme", chosenTheme[0])
                    .putString("language", chosenLanguage[0]).commit()) {
                showError(new IOException(getString(R.string.settings_save_error))); return;
            }
            saveDraft();
            dialog.dismiss();
            if (!current.equals(chosenLanguage[0])) {
                getSystemService(android.app.LocaleManager.class)
                        .setApplicationLocales(android.os.LocaleList.forLanguageTags(chosenLanguage[0]));
            } else if (!selected.equals(chosenTheme[0])) recreate();
        });
        apply.setTextColor(Color.BLACK);
        apply.setBackground(buttonSurface(ACCENT, 28));
        apply.setMinHeight(dp(56));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(-1, -2);
        applyParams.topMargin = dp(16);
        layout.addView(apply, applyParams);
        showFull(dialog);
    }

    private void selectSetting(List<View> options, String value) {
        for (View option : options) {
            boolean selected = value.equals(option.getTag());
            option.setSelected(selected);
            option.setStateDescription(getString(selected ? R.string.option_selected : R.string.option_not_selected));
            GradientDrawable surface = rounded(selected ? (BG == Color.BLACK ? 0xFF3C3218 : 0xFFFFF3CC) : CONTROL, 18);
            if (selected) surface.setStroke(dp(2), ACCENT);
            option.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFC107), surface, rounded(Color.WHITE, 18)));
            View check = option.findViewWithTag("selection");
            if (check != null) check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private android.widget.ImageButton iconButton(int icon, int description, View.OnClickListener action) {
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(INK));
        button.setBackground(buttonSurface(CONTROL, 24));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setContentDescription(getString(description));
        button.setMinimumWidth(dp(48)); button.setMinimumHeight(dp(48));
        button.setOnClickListener(action);
        return button;
    }

    private View settingsButton() {
        return iconButton(R.drawable.ic_settings, R.string.settings, view -> {
            if (busy) showError(new IOException(getString(R.string.busy)));
            else showSettings();
        });
    }

    void openEditor() {
        editing = true;
        pack = null;
        packs.clear();
        images.evictAll();
        buildScreen();
        List<String> selected = List.copyOf(draft.packs);
        List<String> missing = new ArrayList<>();
        work(getString(R.string.loading_template), () -> {
            java.util.concurrent.ExecutorService loaders = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(1, Math.min(4, selected.size())));
            try {
                List<java.util.concurrent.Future<IconRepository.Pack>> pending = new ArrayList<>();
                for (String packageName : selected) pending.add(loaders.submit(() -> repository.load(packageName)));
                apps = repository.apps();
                List<IconRepository.Pack> loaded = new ArrayList<>();
                android.content.pm.PackageManager.NameNotFoundException absent = null;
                for (int i = 0; i < selected.size(); i++) {
                    try { loaded.add(pending.get(i).get()); }
                    catch (java.util.concurrent.ExecutionException error) {
                        if (error.getCause() instanceof android.content.pm.PackageManager.NameNotFoundException notFound) {
                            missing.add(selected.get(i)); absent = notFound;
                        } else if (error.getCause() instanceof Exception cause) throw cause;
                        else throw error;
                    }
                }
                if (loaded.isEmpty() && absent != null) throw absent;
                return loaded;
            } finally { loaders.shutdownNow(); }
        }, loaded -> {
            packs.addAll(loaded); pack = IconRepository.combine(packs); refresh();
            if (!missing.isEmpty()) showError(new IOException(getString(R.string.packs_missing, String.join(", ", missing))));
        }, () -> {}, true);
    }

    private void prepareScreen() {
        root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), 0, dp(18), 0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            int margin = Math.max(dp(20), (view.getWidth() - safe.left - safe.right - dp(840)) / 2);
            view.setPadding(margin + safe.left, safe.top, margin + safe.right, safe.bottom);
            return insets;
        });
        setContentView(root);
    }

    private void showTemplates() {
        editing = false;
        prepareScreen();
        TextView heading = text(getString(R.string.templates), 32, INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMinHeight(dp(72));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout header = row();
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(settingsButton(), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        root.addView(text(getString(R.string.templates_help), 14, MUTED));
        Button create = button(R.string.new_theme, view -> {
            String tree = draft.tree;
            draft = new Draft();
            draft.tree = tree;
            saveDraft();
            openEditor();
        });
        create.setTextColor(Color.BLACK);
        create.setBackground(buttonSurface(ACCENT, 28));
        root.addView(create);
        if (draft.hasUnsavedChanges(this)) {
            root.addView(button(R.string.resume_draft, view -> openEditor()));
        }
        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(BG));
        list.setDividerHeight(dp(12));
        list.setPadding(0, dp(16), 0, dp(12));
        list.setClipToPadding(false);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView empty = text(getString(R.string.no_templates), 14, MUTED);
        empty.setGravity(Gravity.CENTER);
        root.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setEmptyView(empty);
        status = null;
        Map<String, android.graphics.drawable.Drawable> badges = new java.util.HashMap<>();
        work(() -> {
            List<TemplateStore.Entry> entries = templates.list();
            for (TemplateStore.Entry entry : entries) {
                String packageName = entry.settings().getString("pack");
                if (badges.containsKey(packageName)) continue;
                try { badges.put(packageName, getPackageManager().getApplicationIcon(packageName)); }
                catch (android.content.pm.PackageManager.NameNotFoundException error) {
                    Log.w("IconManager", "Иконпак шаблона не установлен: " + packageName);
                    badges.put(packageName, null);
                }
            }
            return entries;
        }, entries -> {
            list.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return entries.size(); }
                @Override public Object getItem(int position) { return entries.get(position); }
                @Override public long getItemId(int position) { return position; }
                @Override public View getView(int position, View convert, ViewGroup parent) {
                    TemplateStore.Entry entry = entries.get(position);
                    LinearLayout item;
                    if (convert == null) {
                        item = row();
                        item.setPadding(dp(16), dp(18), dp(12), dp(18));
                        item.setBackground(buttonSurface(PANEL, 24));
                        ImageView icon = new ImageView(MainActivity.this);
                        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        item.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
                        TextView label = text("", 16, INK);
                        label.setPadding(dp(16), 0, 0, 0);
                        item.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                        Button delete = button(R.string.delete_template, view -> {});
                        delete.setFocusable(false);
                        delete.setMinWidth(dp(64));
                        delete.setMinimumWidth(dp(64));
                        delete.setPadding(dp(12), dp(8), dp(12), dp(8));
                        item.addView(delete, new LinearLayout.LayoutParams(-2, -2));
                    } else item = (LinearLayout) convert;
                    var badge = badges.get(entry.settings().optString("pack"));
                    ImageView icon = (ImageView) item.getChildAt(0);
                    if (badge == null) icon.setImageResource(R.drawable.app_icon);
                    else icon.setImageDrawable(badge);
                    icon.setContentDescription(entry.packLabel());
                    String details = entry.name() + "\n" + entry.settings().optString("title")
                            + (badge == null ? "\n" + getString(R.string.template_pack_missing) : "");
                    android.text.SpannableString caption = new android.text.SpannableString(details);
                    caption.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0,
                            entry.packLabel().length(), 0);
                    caption.setSpan(new android.text.style.ForegroundColorSpan(MUTED),
                            entry.packLabel().length(), details.length(), 0);
                    caption.setSpan(new android.text.style.RelativeSizeSpan(.8f),
                            entry.packLabel().length(), details.length(), 0);
                    ((TextView) item.getChildAt(1)).setText(caption);
                    Button delete = (Button) item.getChildAt(2);
                    delete.setContentDescription(getString(R.string.delete_template_named, entry.name()));
                    delete.setOnClickListener(view -> confirmTemplateDeletion(entry));
                    return item;
                }

            });
            list.setOnItemClickListener((parent, view, index, id) ->
                    work(getString(R.string.loading_template), () -> templates.open(entries.get(index).id()), loaded -> {
                        draft = loaded;
                        saveDraft();
                        markDraftSaved();
                        openEditor();
                    }));
        });
    }

    private void confirmTemplateDeletion(TemplateStore.Entry entry) {
        deleteDialog = new AlertDialog.Builder(this).setTitle(R.string.delete_template_title)
                .setMessage(getString(R.string.delete_template_message, entry.name()))
                .setPositiveButton(R.string.delete_template, (dialog, which) ->
                        work(() -> { templates.delete(entry.id()); return entry.id(); }, deleted -> {
                            if (draft.templateId.equals(deleted)) {
                                draft.templateId = "";
                                saveDraft();
                            }
                            showTemplates();
                        }))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void addDraftStatus() {
        status = text(getString(R.string.ready), 13, MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildScreen() {
        prepareScreen();
        LinearLayout header = row();
        TextView heading = text(getString(R.string.app_name), 24, INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMinHeight(dp(64));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button reset = button(R.string.templates, view -> { saveDraft(); showTemplates(); });
        header.addView(reset, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        settingsParams.setMarginStart(dp(12));
        header.addView(settingsButton(), settingsParams);
        root.addView(header);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column();
        scroll.addView(body);
        body.addView(text(getString(R.string.tagline), 28, INK));
        body.addView(text(getString(R.string.intro), 14, MUTED));
        LinearLayout packCard = card(body, R.string.pack);
        packList = column();
        packCard.addView(packList);
        packInfo = text("", 13, MUTED);
        packInfo.setPadding(0, dp(4), 0, dp(4));
        packCard.addView(packInfo);
        addPackButton = button(R.string.add_pack, view -> choosePack());
        packCard.addView(addPackButton);
        LinearLayout identity = card(body, 0);
        title = edit(R.string.title_hint, draft.title);
        title.setSingleLine(true);
        TextView titleLabel = text(getString(R.string.theme_title), 14, MUTED);
        title.setId(View.generateViewId());
        titleLabel.setLabelFor(title.getId());
        identity.addView(titleLabel); identity.addView(title);
        identity.addView(text(getString(R.string.filename_help), 13, MUTED));
        watch(title, value -> { draft.title = value; saveDraft(); updateExportEnabled(); });
        coverSection = new OptionalSection(body, R.string.cover, !draft.coverPath.isEmpty());
        LinearLayout cover = coverSection.content;
        LinearLayout coverRow = row();
        coverPreview = new ImageView(this);
        coverPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coverPreview.setContentDescription(getString(R.string.cover));
        coverRow.addView(coverPreview, new LinearLayout.LayoutParams(dp(72), dp(128)));
        LinearLayout coverButtons = column();
        coverButtons.addView(button(R.string.choose_cover, view -> openFile(COVER, "image/*")));
        discardCover = button(R.string.discard_cover, view -> {
            draft.coverPath = ""; saveDraft(); refresh();
        });
        coverButtons.addView(discardCover);
        autoCoverInfo = text(getString(R.string.auto_cover), 13, MUTED);
        coverButtons.addView(autoCoverInfo);
        LinearLayout.LayoutParams coverButtonParams = new LinearLayout.LayoutParams(0, -2, 1);
        coverButtonParams.leftMargin = dp(16);
        coverRow.addView(coverButtons, coverButtonParams);
        cover.addView(coverRow);
        wallpaperSection = new OptionalSection(body, R.string.wallpaper, !draft.wallpaperPath.isEmpty());
        LinearLayout wallpaper = wallpaperSection.content;
        wallpaper.addView(text(getString(R.string.wallpaper_help), 13, MUTED));
        LinearLayout wallpaperRow = row();
        wallpaperPreview = new ImageView(this);
        wallpaperPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wallpaperPreview.setContentDescription(getString(R.string.wallpaper));
        wallpaperRow.addView(wallpaperPreview, new LinearLayout.LayoutParams(dp(72), dp(128)));
        LinearLayout wallpaperButtons = column();
        wallpaperButtons.addView(button(R.string.choose_wallpaper, view -> openFile(WALLPAPER, "image/*")));
        removeWallpaper = button(R.string.remove_wallpaper, view -> {
            draft.wallpaperPath = ""; saveDraft(); refresh();
        });
        wallpaperButtons.addView(removeWallpaper);
        wallpaperRow.addView(wallpaperButtons, new LinearLayout.LayoutParams(0, -2, 1));
        wallpaper.addView(wallpaperRow);
        overlaySection = new OptionalSection(body, R.string.overlay_title, !draft.overlay.isEmpty());
        LinearLayout overlay = overlaySection.content;
        overlay.addView(text(getString(R.string.overlay_help), 13, MUTED));
        LinearLayout overlayButtons = row();
        overlayButtons.addView(button(R.string.choose_overlay, view -> openFile(OVERLAY_FILE, "image/png")),
                new LinearLayout.LayoutParams(0, -2, 1));
        removeOverlay = button(R.string.remove_overlay, view -> {
            draft.overlay = ""; draft.overlayAll = false; saveDraft(); refresh();
        });
        LinearLayout.LayoutParams removeOverlayParams = new LinearLayout.LayoutParams(0, -2, 1);
        removeOverlayParams.leftMargin = dp(6);
        overlayButtons.addView(removeOverlay, removeOverlayParams);
        overlay.addView(overlayButtons);
        overlayScope = new RadioGroup(this);
        for (int label : new int[]{R.string.overlay_fallback, R.string.overlay_all}) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId()); option.setText(label);
            overlayScope.addView(option);
        }
        overlayScope.check(overlayScope.getChildAt(draft.overlayAll ? 1 : 0).getId());
        overlayScope.setOnCheckedChangeListener((group, id) -> {
            draft.overlayAll = id == overlayScope.getChildAt(1).getId(); saveDraft(); refresh();
        });
        overlay.addView(overlayScope);
        packCard = card(body, R.string.theme_options);
        packCard.addView(text(getString(R.string.export_scope), 16, INK));
        exportScope = new RadioGroup(this);
        RadioButton installedOnly = new RadioButton(this);
        installedOnly.setId(View.generateViewId());
        installedOnly.setText(R.string.scope_installed);
        RadioButton entirePack = new RadioButton(this);
        entirePack.setId(View.generateViewId());
        entirePack.setText(R.string.scope_full);
        exportScope.addView(installedOnly); exportScope.addView(entirePack);
        exportScope.check(draft.fullPack ? entirePack.getId() : installedOnly.getId());
        exportScope.setOnCheckedChangeListener((group, id) -> {
            draft.fullPack = id == entirePack.getId(); saveDraft(); refresh();
        });
        packCard.addView(exportScope);
        packCard.addView(text(getString(R.string.scope_help), 13, MUTED));
        packCard.addView(text(getString(R.string.fallback_shape), 16, INK));
        LinearLayout shapeRow = row();
        shapePreview = new ImageView(this);
        shapePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        shapePreview.setPadding(dp(8), dp(8), dp(8), dp(8));
        shapePreview.setCropToPadding(true);
        shapeRow.addView(shapePreview, new LinearLayout.LayoutParams(dp(64), dp(64)));
        shapeButton = button(R.string.choose_shape, view -> showShapePicker());
        shapeRow.addView(shapeButton, new LinearLayout.LayoutParams(0, -2, 1));
        packCard.addView(shapeRow);
        packCard.addView(text(getString(R.string.fallback_shape_help), 13, MUTED));
        CheckBox folderBackground = new CheckBox(this);
        folderBackground.setText(R.string.folder_background);
        folderBackground.setTextColor(INK);
        folderBackground.setChecked(draft.clearFolderBackground);
        folderBackground.setOnCheckedChangeListener((view, checked) -> {
            draft.clearFolderBackground = checked; saveDraft();
        });
        packCard.addView(folderBackground);
        packCard.addView(text(getString(R.string.folder_background_help), 13, MUTED));
        LinearLayout appCard = card(body, R.string.apps);
        appCard.addView(text(getString(R.string.apps_help), 14, MUTED));
        appInfo = text("", 14, ACCENT_TEXT); appCard.addView(appInfo);
        appCard.addView(button(R.string.apps, view -> {
            if (pack == null) showError(new IOException(getString(R.string.no_pack)));
            else showApps();
        }));
        LinearLayout destination = card(body, R.string.destination);
        destination.addView(text(getString(R.string.destination_help), 14, MUTED));
        destinationButton = button(R.string.choose_destination, view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Honor/Themes"));
            startActivityForResult(intent, DIRECTORY);
        });
        destination.addView(destinationButton);
        destinationInfo = text("", 13, ACCENT_TEXT); destination.addView(destinationInfo);
        templateHelp = text(getString(draft.templateId.isEmpty()
                ? R.string.template_create_help : R.string.template_update_help), 13, MUTED);
        body.addView(templateHelp);
        saveAsNew = new CheckBox(this);
        saveAsNew.setText(R.string.save_as_new_template);
        saveAsNew.setVisibility(draft.templateId.isEmpty() ? View.GONE : View.VISIBLE);
        body.addView(saveAsNew);
        addDraftStatus();
        Button export = button(R.string.export, view -> export());
        exportButton = export;
        export.setTextColor(Color.BLACK); export.setBackground(buttonSurface(ACCENT, 28));
        export.setMinHeight(dp(56));
        root.addView(export, new LinearLayout.LayoutParams(-1, -2));
        refresh();
    }

    private void refresh() {
        refreshPacks();
        int matched = 0, manual = 0;
        for (IconRepository.App app : apps) {
            if (pack != null && pack.selected(app, draft.overrides) != null) matched++;
            if (draft.overrides.containsKey(app.key()) && !draft.overrides.get(app.key()).isEmpty()) manual++;
        }
        appInfo.setText(getString(R.string.app_counts, apps.size(), matched, manual));
        if (pack != null) {
            appInfo.append(getString(R.string.export_count,
                    ThemeEngine.exportCount(apps, pack, draft.overrides, draft.fullPack)));
        }
        destinationInfo.setText(draft.tree.isEmpty() ? getString(R.string.no_destination) : "Honor/Themes");
        destinationButton.setText(draft.tree.isEmpty() ? R.string.choose_destination : R.string.change_destination);
        shapeButton.setText(getString(R.string.current_shape, getString(draft.shape.label)));
        refreshShapePreview();
        updateExportEnabled();
        if (!draft.coverPath.isEmpty()) {
            Bitmap bitmap = BitmapFactory.decodeFile(new File(getFilesDir(), draft.coverPath).getPath());
            coverPreview.setImageBitmap(bitmap);
        } else {
            coverPreview.setImageResource(R.drawable.app_icon);
        }
        wallpaperPreview.setImageBitmap(draft.wallpaperPath.isEmpty() ? null
                : BitmapFactory.decodeFile(new File(getFilesDir(), draft.wallpaperPath).getPath()));
        boolean hasWallpaper = !draft.wallpaperPath.isEmpty();
        removeWallpaper.setVisibility(hasWallpaper ? View.VISIBLE : View.GONE);
        wallpaperPreview.setVisibility(hasWallpaper ? View.VISIBLE : View.GONE);
        View wallpaperButtons = (View) removeWallpaper.getParent();
        ((ViewGroup.MarginLayoutParams) wallpaperButtons.getLayoutParams()).leftMargin = hasWallpaper ? dp(16) : 0;
        wallpaperButtons.requestLayout();
        discardCover.setVisibility(draft.coverPath.isEmpty() ? View.GONE : View.VISIBLE);
        autoCoverInfo.setVisibility(draft.coverPath.isEmpty() ? View.VISIBLE : View.GONE);
        removeOverlay.setVisibility(draft.overlay.isEmpty() ? View.GONE : View.VISIBLE);
        View chooseOverlay = ((ViewGroup) removeOverlay.getParent()).getChildAt(0);
        ((ViewGroup.MarginLayoutParams) chooseOverlay.getLayoutParams()).rightMargin = draft.overlay.isEmpty() ? 0 : dp(6);
        chooseOverlay.requestLayout();
        overlayScope.setVisibility(draft.overlay.isEmpty() ? View.GONE : View.VISIBLE);
        coverSection.update(!draft.coverPath.isEmpty());
        wallpaperSection.update(!draft.wallpaperPath.isEmpty());
        overlaySection.update(!draft.overlay.isEmpty());
    }

    private void updateExportEnabled() {
        if (!editing || exportButton == null) return;
        boolean ready = !busy && pack != null && !draft.title.isBlank() && !draft.tree.isBlank();
        exportButton.setEnabled(ready);
        exportButton.setAlpha(ready ? 1f : .4f);
    }

    private void refreshShapePreview() {
        IconRepository.Pack currentPack = pack;
        List<IconRepository.App> candidates = apps.stream()
                .filter(app -> currentPack == null || currentPack.automatic(app) == null).toList();
        IconShape shape = draft.shape;
        String overlayReference = draft.overlay;
        String key = String.join(",", draft.packs) + ":" + shape + ":" + overlayReference + ":" + draft.overlayAll;
        if (key.equals(shapePreview.getTag())) return;
        ImageView target = shapePreview;
        target.setTag(key);
        target.setImageDrawable(null);
        thumbnails.execute(() -> {
            try {
                IconRepository.App example = null;
                android.graphics.drawable.Drawable drawable = null;
                for (IconRepository.App candidate : candidates) {
                    var icon = getPackageManager().getActivityIcon(candidate.component());
                    if (drawable == null || icon instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                        example = candidate;
                        drawable = icon;
                    }
                    if (icon instanceof android.graphics.drawable.AdaptiveIconDrawable) break;
                }
                if (drawable == null) drawable = getDrawable(R.drawable.app_icon);
                String exampleName = example == null ? getString(R.string.app_name) : example.label();
                Bitmap source = IconShape.previewSource(drawable, shape != IconShape.NONE);
                Bitmap preview;
                try {
                    preview = shape.preview(source, currentPack == null ? IconShape.SIZE - 2 * IconShape.INSET
                            : currentPack.fallbackSize());
                } finally { source.recycle(); }
                Bitmap overlay = IconOverlay.load(this, overlayReference);
                try { IconOverlay.draw(preview, overlay); }
                finally { if (overlay != null) overlay.recycle(); }
                runOnUiThread(() -> {
                    if (!isDestroyed() && key.equals(target.getTag())) {
                        target.setImageBitmap(preview);
                        target.setContentDescription(getString(R.string.shape_example, getString(shape.label), exampleName));
                    }
                    else preview.recycle();
                });
            } catch (android.content.pm.PackageManager.NameNotFoundException | IOException | RuntimeException error) {
                Log.e("IconManager", "Не удалось показать пример формы", error);
                runOnUiThread(() -> { if (!isDestroyed() && key.equals(target.getTag())) showError(error); });
            }
        });
    }

    private void openFile(int code, String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime)
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, code);
    }

    private void showShapePicker() {
        Dialog dialog = fullDialog();
        shapeDialog = dialog;
        LinearLayout layout = dialogLayout(dialog, getString(R.string.choose_shape));
        LinearLayout header = (LinearLayout) layout.getChildAt(0);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        backParams.setMarginEnd(dp(12));
        header.addView(iconButton(R.drawable.ic_back, R.string.back, view -> dialog.dismiss()), 0, backParams);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        scroll.addView(content);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout stage = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int height = Math.round(View.MeasureSpec.getSize(widthSpec)
                        * (float) ShapePreview.HEIGHT / ShapePreview.WIDTH);
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            }
        };
        stage.setBackground(rounded(CONTROL, 20));
        stage.setClipToOutline(true);
        ImageView preview = new ImageView(this);
        preview.setTag("shape-scene");
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        ProgressBar loading = new ProgressBar(this);
        loading.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        loading.setContentDescription(getString(R.string.loading));
        stage.addView(loading, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        content.addView(stage, new LinearLayout.LayoutParams(-1, -2));
        String wallpaperPath = draft.wallpaperPath, overlayReference = draft.overlay;
        content.addView(text(getString(wallpaperPath.isEmpty()
                ? R.string.shape_preview_gradient : R.string.shape_preview_wallpaper), 12, MUTED));
        content.addView(text(getString(R.string.shape_preview_help), 13, MUTED));
        IconShape[] selected = {draft.shape};
        ShapePreview[] scene = new ShapePreview[1];
        Bitmap[] displayed = new Bitmap[1];
        List<Bitmap> silhouettes = new ArrayList<>();
        List<View> choices = new ArrayList<>();
        List<IconRepository.App> examples = ShapePreview.examples(apps, pack, Map.copyOf(draft.overrides),
                getResources().getConfiguration().getLocales().get(0));
        IconRepository.Pack currentPack = pack;
        Runnable render = () -> {
            for (View choice : choices) {
                boolean active = choice.getTag() == selected[0];
                choice.setSelected(active);
                choice.setStateDescription(getString(active ? R.string.option_selected : R.string.option_not_selected));
                GradientDrawable background = rounded(active ? CONTROL : Color.TRANSPARENT, 18);
                if (active) background.setStroke(dp(2), ACCENT);
                choice.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFC107),
                        background, rounded(Color.WHITE, 18)));
                choice.findViewWithTag("shape-check").setVisibility(active ? View.VISIBLE : View.GONE);
            }
            if (scene[0] == null) return;
            Bitmap image = scene[0].render(selected[0]);
            preview.setImageBitmap(image);
            preview.setContentDescription(getString(R.string.shape_example, getString(selected[0].label),
                    String.join(", ", examples.stream().map(IconRepository.App::label).toList())));
            if (displayed[0] != null) displayed[0].recycle();
            displayed[0] = image;
        };
        List<IconShape> shapes = List.of(IconShape.ROUNDED_SQUARE, IconShape.SQUIRCLE, IconShape.SQUARE, IconShape.CIRCLE,
                IconShape.HEART, IconShape.STAR, IconShape.HEXAGON, IconShape.PENTAGON, IconShape.TEARDROP, IconShape.NONE);
        LinearLayout options = card(content, R.string.fallback_shape);
        LinearLayout line = null;
        for (int i = 0; i < shapes.size(); i++) {
            if (i % 4 == 0) {
                line = row();
                line.setGravity(Gravity.TOP);
                options.addView(line);
            }
            IconShape shape = shapes.get(i);
            LinearLayout choice = column();
            choice.setTag(shape);
            choice.setGravity(Gravity.CENTER_HORIZONTAL);
            choice.setPadding(dp(4), dp(12), dp(4), dp(8));
            choice.setMinimumHeight(dp(136));
            choice.setFocusable(true);
            choice.setContentDescription(getString(shape.label));
            FrameLayout illustration = new FrameLayout(this);
            ImageView silhouette = new ImageView(this);
            Bitmap icon = shape == IconShape.NONE ? IconShape.previewSource(getDrawable(R.drawable.app_icon), false)
                    : shape.mask(176);
            silhouettes.add(icon);
            silhouette.setImageBitmap(icon);
            if (shape != IconShape.NONE) silhouette.setImageTintList(ColorStateList.valueOf(i < 4 ? 0xFFFF9B91 : 0xFFA3AEFF));
            illustration.addView(silhouette, new FrameLayout.LayoutParams(-1, -1));
            ImageView check = new ImageView(this);
            check.setTag("shape-check");
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            illustration.addView(check, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            illustration.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            choice.addView(illustration, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView label = text(getString(shape.label), 12, INK);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            choice.addView(label, new LinearLayout.LayoutParams(-1, -2));
            View.OnClickListener select = view -> { selected[0] = shape; render.run(); };
            choice.setOnClickListener(select);
            label.setOnClickListener(select);
            line.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
            choices.add(choice);
        }
        while (line.getChildCount() < 4) line.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        Button apply = button(R.string.apply_settings, view -> {
            if (draft.shape != selected[0]) {
                draft.shape = selected[0];
                saveDraft();
                refresh();
            }
            dialog.dismiss();
        });
        apply.setMinHeight(dp(56));
        apply.setTextColor(Color.BLACK);
        apply.setBackground(buttonSurface(ACCENT, 28));
        apply.setEnabled(false);
        layout.addView(apply, new LinearLayout.LayoutParams(-1, -2));
        dialog.setOnDismissListener(ignored -> {
            preview.setImageDrawable(null);
            if (displayed[0] != null) displayed[0].recycle();
            if (scene[0] != null) scene[0].close();
            for (Bitmap icon : silhouettes) icon.recycle();
            if (shapeDialog == dialog) shapeDialog = null;
        });
        render.run();
        showFull(dialog);
        thumbnails.execute(() -> {
            try {
                int visibleSize = currentPack == null ? IconShape.SIZE - 2 * IconShape.INSET : currentPack.fallbackSize();
                ShapePreview loaded = ShapePreview.load(this, examples, visibleSize, wallpaperPath, overlayReference);
                runOnUiThread(() -> {
                    if (isDestroyed() || !dialog.isShowing()) { loaded.close(); return; }
                    scene[0] = loaded;
                    loading.setVisibility(View.GONE);
                    render.run();
                    apply.setEnabled(true);
                });
            } catch (IOException | RuntimeException error) {
                Log.e("IconManager", "Shape preview failed", error);
                runOnUiThread(() -> {
                    if (!isDestroyed() && dialog.isShowing()) {
                        loading.setVisibility(View.GONE);
                        showError(error);
                    }
                });
            }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == OVERLAY_FILE) {
            work(() -> IconOverlay.read(this, uri), this::previewOverlay);
        } else if (requestCode == ICON_FILE) {
            if (pendingIconApp == null) { showError(new IOException(App.text(R.string.choose_app_again))); return; }
            String appKey = pendingIconApp;
            Runnable changed = pendingIconChanged;
            work(() -> FileIcons.read(this, uri), image -> previewFileIcon(appKey, changed, image));
        } else if (requestCode == COVER) {
            work(() -> ThemeEngine.readCover(getContentResolver(), uri), this::cropCover);
        } else if (requestCode == WALLPAPER) {
            work(() -> {
                Bitmap image = ThemeEngine.readWallpaper(getContentResolver(), uri);
                android.util.AtomicFile file = new android.util.AtomicFile(new File(getFilesDir(), "wallpaper.jpg"));
                try {
                    FileOutputStream output = file.startWrite();
                    try {
                        if (!image.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                            throw new IOException(App.text(R.string.wallpaper_save_error));
                        }

                        file.finishWrite(output);
                    } catch (Exception error) {
                        file.failWrite(output);
                        throw error;
                    }
                } finally { image.recycle(); }
                return "wallpaper.jpg";
            }, path -> { draft.wallpaperPath = path; saveDraft(); refresh(); });
        } else if (requestCode == DIRECTORY) {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            work(() -> {
                if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0
                        || (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                    throw new IOException(getString(R.string.access_lost));
                }
                ThemeEngine.validateTree(this, uri);
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                return uri.toString();
            }, tree -> { draft.tree = tree; saveDraft(); refresh(); });
        }
    }

    private void previewOverlay(Bitmap overlay) {
        Dialog dialog = fullDialog();
        LinearLayout content = scrollingDialogLayout(dialog, getString(R.string.overlay_title));
        content.addView(text(getString(R.string.overlay_help), 14, MUTED));
        Bitmap source = IconShape.previewSource(getDrawable(R.drawable.app_icon));
        Bitmap preview = draft.shape.preview(source);
        source.recycle();
        IconOverlay.draw(preview, overlay);
        ImageView image = new ImageView(this);
        image.setImageBitmap(preview);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(getString(R.string.overlay_preview));
        content.addView(image, new LinearLayout.LayoutParams(-1, dp(192)));
        content.addView(button(R.string.use_overlay, view -> {
            Bitmap copy = overlay.copy(Bitmap.Config.ARGB_8888, false);
            dialog.dismiss();
            work(() -> {
                try { return FileIcons.save(this, copy); }
                finally { copy.recycle(); }
            }, reference -> { draft.overlay = reference; saveDraft(); refresh(); });
        }));
        overlayDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            preview.recycle(); overlay.recycle();
            if (overlayDialog == dialog) overlayDialog = null;
        });
        showFull(dialog);
    }

    private void cropCover(Bitmap source) {
        Dialog dialog = fullDialog();
        cropDialog = dialog;
        LinearLayout layout = dialogLayout(dialog, getString(R.string.crop_cover));
        layout.addView(text(getString(R.string.crop_help), 14, MUTED));
        CoverCropView crop = new CoverCropView(this, source);
        layout.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
        SeekBar zoom = new SeekBar(this);
        zoom.setMax(200);
        zoom.setContentDescription(getString(R.string.crop_zoom));
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                crop.setZoom(1 + value / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        layout.addView(zoom, new LinearLayout.LayoutParams(-1, dp(48)));
        layout.addView(button(R.string.crop_use, view -> {
            Bitmap fitted = crop.crop();
            dialog.dismiss();
            work(() -> {
                File temporary = new File(getFilesDir(), "cover-new.jpg");
                try {
                    try (var output = new FileOutputStream(temporary)) {
                        if (!fitted.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw new IOException(App.text(R.string.cover_save_error));
                    }
                    java.nio.file.Files.move(temporary.toPath(), new File(getFilesDir(), "cover.jpg").toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    fitted.recycle();
                    if (temporary.exists() && !temporary.delete()) Log.w("IconManager", "Не удалён временный файл обложки");
                }
                return "cover.jpg";
            }, path -> { draft.coverPath = path; saveDraft(); refresh(); });
        }));
        dialog.setOnDismissListener(ignored -> {
            source.recycle();
            if (cropDialog == dialog) cropDialog = null;
        });
        showFull(dialog);
    }

    private void choosePack() {
        if (packs.size() >= IconRepository.MAX_PACKS) {
            showError(new IOException(getString(R.string.pack_limit, IconRepository.MAX_PACKS))); return;
        }
        choosePack(this::selectPack, java.util.Set.copyOf(draft.packs));
    }

    private void choosePack(Consumer<IconRepository.Pack> selected) {
        choosePack(selected, java.util.Set.of());
    }

    private void choosePack(Consumer<IconRepository.Pack> selected, java.util.Set<String> excluded) {
        Dialog dialog = fullDialog();
        packDialog = dialog;
        LinearLayout layout = dialogLayout(dialog, getString(R.string.choose_pack));
        LinearLayout content = column();
        layout.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        Runnable stopLoading = packSkeleton(content);
        dialog.setOnDismissListener(ignored -> {
            stopLoading.run();
            if (packDialog == dialog) packDialog = null;
        });
        showFull(dialog);
        work(() -> {
            List<IconRepository.PackInfo> found = new ArrayList<>(repository.packs());
            found.removeIf(info -> excluded.contains(info.packageName()));
            return found;
        }, packs -> {
            stopLoading.run();
            if (!dialog.isShowing()) return;
            content.removeAllViews();
            if (packs.isEmpty()) {
                content.addView(text(getString(R.string.no_packs), 16, MUTED));
                return;
            }
            ListView list = new ListView(this);
            list.setDividerHeight(dp(1));
            list.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return packs.size(); }
                @Override public Object getItem(int position) { return packs.get(position); }
                @Override public long getItemId(int position) { return position; }
                @Override public View getView(int position, View convert, ViewGroup parent) {
                    LinearLayout item;
                    if (convert == null) {
                        item = row(); item.setPadding(dp(8), dp(12), dp(8), dp(12));
                        ImageView icon = new ImageView(MainActivity.this);
                        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        item.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
                        TextView label = text("", 17, INK);
                        label.setPadding(dp(16), 0, 0, 0);
                        item.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                    } else item = (LinearLayout) convert;
                    IconRepository.PackInfo info = packs.get(position);
                    ((ImageView) item.getChildAt(0)).setImageBitmap(info.badge());
                    ((TextView) item.getChildAt(1)).setText(info.label());
                    return item;
                }
            });
            content.addView(list, new LinearLayout.LayoutParams(-1, -1));
            list.setOnItemClickListener((parent, view, index, id) -> {
                IconRepository.PackInfo chosen = packs.get(index);
                Runnable load = () -> {
                    enabled(layout.getChildAt(0), false);
                    dialog.setCancelable(false);
                    Runnable stop = packSkeleton(content);
                    dialog.setOnDismissListener(ignored -> {
                        stop.run();
                        if (packDialog == dialog) packDialog = null;
                    });
                    work(() -> repository.load(chosen.packageName()), loaded -> {
                        dialog.dismiss();
                        selected.accept(loaded);
                    }, dialog::dismiss);
                };
                load.run();
            });
        }, dialog::dismiss);
    }

    void selectPack(IconRepository.Pack loaded) {
        if (!draft.packs.contains(loaded.packageName)) {
            if (packs.size() >= IconRepository.MAX_PACKS) {
                showError(new IOException(getString(R.string.pack_limit, IconRepository.MAX_PACKS))); return;
            }
            packs.add(loaded);
        }
        if (title.getText().toString().isBlank()) title.setText(packs.get(0).label);
        packsChanged();
    }

    void removePack(int index) {
        packs.remove(index);
        packsChanged();
    }

    void movePack(int from, int to) {
        if (from == to || to < 0 || to >= packs.size()) return;
        packs.add(to, packs.remove(from));
        packsChanged();
    }

    private void packsChanged() {
        draft.packs.clear();
        for (IconRepository.Pack member : packs) draft.packs.add(member.packageName);
        pack = IconRepository.combine(packs);
        images.evictAll(); saveDraft(); refresh();
    }

    private void refreshPacks() {
        packList.removeAllViews();
        for (int i = 0; i < packs.size(); i++) packList.addView(packRow(i));
        packInfo.setVisibility(packs.size() == 1 ? View.GONE : View.VISIBLE);
        packInfo.setText(packs.isEmpty() ? R.string.no_pack : R.string.pack_priority_help);
        addPackButton.setVisibility(packs.size() >= IconRepository.MAX_PACKS ? View.GONE : View.VISIBLE);
        addPackButton.setText(packs.isEmpty() ? R.string.choose_pack : R.string.add_pack);
    }

    private View packRow(int index) {
        IconRepository.Pack member = packs.get(index);
        LinearLayout item = row();
        item.setPadding(0, dp(6), 0, dp(6));
        item.setBackground(rounded(PANEL, 12));
        TextView handle = text("≡", 26, MUTED);
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription(getString(R.string.pack_drag, member.label));
        handle.setVisibility(packs.size() > 1 ? View.VISIBLE : View.GONE);
        item.addView(handle, new LinearLayout.LayoutParams(dp(40), dp(48)));
        ImageView badge = new ImageView(this);
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.setImageBitmap(member.badge);
        item.addView(badge, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView label = text(getString(R.string.pack_count, member.label, member.icons.size()), 15, ACCENT_TEXT);
        label.setPadding(dp(12), 0, dp(8), 0);
        item.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Button remove = button(R.string.remove_pack, view -> removePack(packList.indexOfChild(item)));
        remove.setMinWidth(dp(64)); remove.setMinimumWidth(dp(64));
        remove.setPadding(dp(12), dp(6), dp(12), dp(6));
        remove.setContentDescription(getString(R.string.remove_pack_named, member.label));
        item.addView(remove, new LinearLayout.LayoutParams(-2, -2));
        item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                int position = packList.indexOfChild(item);
                if (position > 0) info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        R.id.pack_move_up, getString(R.string.pack_move_up)));
                if (position >= 0 && position < packs.size() - 1) info.addAction(
                        new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                                R.id.pack_move_down, getString(R.string.pack_move_down)));
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                int position = packList.indexOfChild(item);
                if (action == R.id.pack_move_up) { movePack(position, position - 1); return true; }
                if (action == R.id.pack_move_down) { movePack(position, position + 1); return true; }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        float[] start = new float[1];
        handle.setOnTouchListener((view, event) -> {
            int from = packList.indexOfChild(item);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    start[0] = event.getRawY();
                    item.setElevation(dp(8)); item.setAlpha(.9f);
                }
                case MotionEvent.ACTION_MOVE -> {
                    float offset = event.getRawY() - start[0];
                    item.setTranslationY(offset);
                    int target = dragTarget(from, offset);
                    for (int i = 0; i < packList.getChildCount(); i++) {
                        if (i == from) continue;
                        float shift = i > from && i <= target ? -item.getHeight()
                                : i < from && i >= target ? item.getHeight() : 0;
                        packList.getChildAt(i).setTranslationY(shift);
                    }
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    int target = event.getActionMasked() == MotionEvent.ACTION_UP
                            ? dragTarget(from, item.getTranslationY()) : from;
                    for (int i = 0; i < packList.getChildCount(); i++) packList.getChildAt(i).setTranslationY(0);
                    item.setElevation(0); item.setAlpha(1);
                    if (target != from) movePack(from, target);
                    else if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                }
                default -> { return false; }
            }
            return true;
        });
        return item;
    }

    private int dragTarget(int from, float offset) {
        View dragged = packList.getChildAt(from);
        float centre = dragged.getTop() + dragged.getHeight() / 2f + offset;
        int target = from;
        for (int i = 0; i < packList.getChildCount(); i++) {
            View other = packList.getChildAt(i);
            if (i > from && centre > other.getTop()) target = i;
            if (i < from && centre < other.getBottom() && target == from) target = i;
        }
        return target;
    }

    private Runnable packSkeleton(LinearLayout content) {
        content.removeAllViews();
        LinearLayout loading = row();
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        loading.addView(spinner, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView label = text(getString(R.string.loading), 16, MUTED);
        label.setPadding(dp(12), dp(8), 0, dp(8));
        loading.addView(label);
        content.addView(loading);
        LinearLayout placeholders = column();
        placeholders.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (int i = 0; i < 5; i++) {
            LinearLayout item = row(); item.setPadding(dp(8), dp(12), dp(8), dp(12));
            View icon = new View(this); icon.setBackground(rounded(PANEL, 12));
            item.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
            View line = new View(this); line.setBackground(rounded(PANEL, 6));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(140 + i % 3 * 28), dp(18));
            params.leftMargin = dp(16); item.addView(line, params);
            placeholders.addView(item);
        }
        content.addView(placeholders);
        ObjectAnimator pulse = ObjectAnimator.ofFloat(placeholders, View.ALPHA, .35f, 1f);
        pulse.setDuration(700); pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE); pulse.start();
        return pulse::cancel;
    }

    private void showApps() {
        List<IconRepository.App> editableApps = new ArrayList<>(apps);
        java.util.Set<String> known = new java.util.HashSet<>();
        for (IconRepository.App app : apps) known.add(app.key());
        for (String key : draft.overrides.keySet()) {
            if (known.contains(key)) continue;
            var component = IconRepository.component(key);
            if (component != null) editableApps.add(new IconRepository.App(component,
                    getString(R.string.not_installed_app, component.getPackageName())));
        }
        Dialog dialog = fullDialog();
        LinearLayout layout = dialogLayout(dialog, getString(R.string.apps));
        EditText search = edit(R.string.search_apps, "");
        search.setSingleLine(true); layout.addView(search);
        CheckBox missing = new CheckBox(this);
        missing.setText(R.string.only_missing);
        CheckBox systemIcons = new CheckBox(this);
        systemIcons.setText(R.string.system_icons);
        systemIcons.setChecked(draft.systemIcons);
        LinearLayout toggles = row();
        toggles.addView(missing, new LinearLayout.LayoutParams(0, -2, 1));
        toggles.addView(systemIcons, new LinearLayout.LayoutParams(-2, -2));
        layout.addView(toggles);
        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        layout.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        List<IconRepository.App> filtered = new ArrayList<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int position) { return filtered.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convert, ViewGroup parent) {
                LinearLayout item;
                if (convert == null) {
                    item = row(); item.setPadding(dp(8), dp(9), dp(8), dp(9));
                    ImageView image = new ImageView(MainActivity.this);
                    item.addView(image, new LinearLayout.LayoutParams(dp(52), dp(52)));
                    LinearLayout labels = column(); labels.setPadding(dp(12), 0, 0, 0);
                    labels.addView(text("", 16, INK)); labels.addView(text("", 11, MUTED));
                    labels.addView(text("", 12, ACCENT_TEXT));
                    item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                } else item = (LinearLayout) convert;
                IconRepository.App app = filtered.get(position);
                String selected = pack.selected(app, draft.overrides);
                LinearLayout labels = (LinearLayout) item.getChildAt(1);
                ((TextView) labels.getChildAt(0)).setText(app.label());
                ((TextView) labels.getChildAt(1)).setText(app.key());
                String detail = selected != null && FileIcons.isFile(selected) ? getString(R.string.icon_from_file) : selected == null
                        ? getString(draft.overrides.containsKey(app.key()) ? R.string.original : R.string.missing)
                        : getString(draft.overrides.containsKey(app.key()) ? R.string.manual : R.string.matched, selected);
                ((TextView) labels.getChildAt(2)).setText(detail);
                ImageView image = (ImageView) item.getChildAt(0);
                if (selected == null && draft.systemIcons) bindSystemImage(image, app);
                else bindImage(image, selected);
                return item;
            }
        };
        list.setAdapter(adapter);
        Runnable filter = () -> {
            String query = search.getText().toString().toLowerCase(Locale.ROOT).trim();
            filtered.clear();
            for (IconRepository.App app : editableApps) {
                if (missing.isChecked() && pack.selected(app, draft.overrides) != null) continue;
                if ((app.label() + " " + app.key()).toLowerCase(Locale.ROOT).contains(query)) filtered.add(app);
            }
            adapter.notifyDataSetChanged();
        };
        watch(search, query -> filter.run());
        missing.setOnCheckedChangeListener((view, checked) -> filter.run());
        systemIcons.setOnCheckedChangeListener((view, checked) -> {
            draft.systemIcons = checked; saveDraft(); adapter.notifyDataSetChanged();
        });
        list.setOnItemClickListener((parent, view, position, id) ->
                showIcons(filtered.get(position), () -> { saveDraft(); filter.run(); refresh(); }));
        filter.run();
        showFull(dialog);
    }

    void showIcons(IconRepository.App app, Runnable changed) {
        Dialog dialog = fullDialog();
        iconDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (iconDialog == dialog) iconDialog = null; });
        LinearLayout layout = dialogLayout(dialog, getString(R.string.pick_icon, app.label()));
        List<IconRepository.Pack> base = pack.members.isEmpty() ? List.of(pack) : pack.members;
        int[] tab = {Math.max(0, base.indexOf(initialSource(app)))};
        IconRepository.Pack[] custom = {null};
        LinearLayout sourceBar = column();
        layout.addView(sourceBar, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = row();
        Button automatic = button(R.string.automatic, view -> {
            draft.overrides.remove(app.key()); changed.run(); dialog.dismiss();
        });
        Button original = button(R.string.keep_original, view -> {
            draft.overrides.put(app.key(), ""); changed.run(); dialog.dismiss();
        });
        LinearLayout.LayoutParams automaticParams = new LinearLayout.LayoutParams(0, -2, 1);
        automaticParams.rightMargin = dp(6);
        LinearLayout.LayoutParams originalParams = new LinearLayout.LayoutParams(0, -2, 1);
        originalParams.leftMargin = dp(6);
        actions.addView(automatic, automaticParams);
        actions.addView(original, originalParams);
        if (pack.selected(app, draft.overrides) == null) {
            original.setVisibility(View.GONE);
            automaticParams.rightMargin = 0;
        }
        if (!draft.overrides.containsKey(app.key())) {
            automatic.setVisibility(View.GONE);
            originalParams.leftMargin = 0;
        }
        if (automatic.getVisibility() == View.GONE && original.getVisibility() == View.GONE) actions.setVisibility(View.GONE);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = dp(8);
        actionParams.bottomMargin = dp(12);
        layout.addView(actions, actionParams);
        EditText search = edit(R.string.search_icons, ""); search.setSingleLine(true);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        if (actions.getVisibility() == View.GONE) searchParams.topMargin = dp(12);
        layout.addView(search, searchParams);
        TextView count = text("", 13, MUTED); layout.addView(count);
        LinearLayout body = row();
        body.setGravity(Gravity.TOP);
        layout.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView railScroll = new ScrollView(this);
        railScroll.setVerticalScrollBarEnabled(false);
        LinearLayout rail = column();
        rail.setPadding(0, dp(4), dp(8), dp(4));
        railScroll.addView(rail);
        body.addView(railScroll, new LinearLayout.LayoutParams(-2, -1));
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT); grid.setColumnWidth(dp(96));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        body.addView(grid, new LinearLayout.LayoutParams(0, -1, 1));
        List<IconChoice> filtered = new ArrayList<>();
        boolean[] mixed = {false};
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int position) { return filtered.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convert, ViewGroup parent) {
                LinearLayout tile;
                if (convert == null) {
                    tile = column(); tile.setGravity(Gravity.CENTER); tile.setPadding(dp(4), dp(8), dp(4), dp(8));
                    FrameLayout frame = new FrameLayout(MainActivity.this);
                    frame.addView(new ImageView(MainActivity.this), new FrameLayout.LayoutParams(dp(64), dp(64)));
                    ImageView origin = new ImageView(MainActivity.this);
                    origin.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    origin.setPadding(dp(2), dp(2), dp(2), dp(2));
                    origin.setBackground(rounded(PANEL, 12));
                    frame.addView(origin, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END | Gravity.BOTTOM));
                    tile.addView(frame, new LinearLayout.LayoutParams(dp(72), dp(68)));
                    TextView name = text("", 10, MUTED); name.setMaxLines(2); name.setGravity(Gravity.CENTER);
                    tile.addView(name, new LinearLayout.LayoutParams(-1, dp(38)));
                } else tile = (LinearLayout) convert;
                IconChoice choice = filtered.get(position);
                FrameLayout frame = (FrameLayout) tile.getChildAt(0);
                ImageView origin = (ImageView) frame.getChildAt(1);
                origin.setVisibility(mixed[0] ? View.VISIBLE : View.GONE);
                origin.setImageBitmap(mixed[0] ? choice.source().badge : null);
                ((TextView) tile.getChildAt(1)).setText(choice.icon().name());
                tile.setContentDescription(choice.icon().name() + " · " + choice.icon().category()
                        + (mixed[0] ? " · " + choice.source().label : ""));
                bindImage((ImageView) frame.getChildAt(0), choice.source(), choice.icon().name());
                return tile;
            }
        };
        grid.setAdapter(adapter);
        Runnable[] refreshPicker = new Runnable[1];
        Consumer<String> filter = query -> {
            String q = query.toLowerCase(Locale.ROOT).trim();
            List<IconRepository.Pack> sources = custom[0] != null ? List.of(custom[0])
                    : q.isEmpty() ? List.of(base.get(tab[0])) : base;
            mixed[0] = sources.size() > 1;
            filtered.clear();
            for (IconRepository.Pack source : sources) {
                for (IconRepository.Icon icon : source.icons.values()) {
                    if ((icon.name() + " " + icon.category()).toLowerCase(Locale.ROOT).contains(q))
                        filtered.add(new IconChoice(source, icon));
                }
            }
            String label = custom[0] != null ? custom[0].label : "";
            if (filtered.isEmpty()) label = label.isEmpty() ? getString(R.string.no_icons) : label + " · " + getString(R.string.no_icons);
            count.setText(label);
            count.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
            boolean tabs = custom[0] == null && base.size() > 1;
            railScroll.setVisibility(tabs ? View.VISIBLE : View.GONE);
            for (int i = 0; i < rail.getChildCount(); i++) {
                IconRepository.Pack member = base.get(i);
                View item = rail.getChildAt(i);
                boolean active = q.isEmpty() ? i == tab[0] : filtered.stream().anyMatch(choice -> choice.source() == member);
                item.setSelected(q.isEmpty() && i == tab[0]);
                item.setBackground(packTab(q.isEmpty() && i == tab[0]));
                item.setAlpha(active || q.isEmpty() ? 1 : .35f);
            }
            adapter.notifyDataSetChanged();
        };
        for (int i = 0; i < base.size(); i++) {
            int index = i;
            IconRepository.Pack member = base.get(i);
            ImageView item = new ImageView(this);
            item.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.setPadding(dp(8), dp(8), dp(8), dp(8));
            item.setImageBitmap(member.badge);
            item.setContentDescription(member.label);
            item.setTooltipText(member.label);
            item.setOnClickListener(view -> {
                if (search.getText().toString().isBlank()) {
                    tab[0] = index; filter.accept(""); grid.setSelection(0);
                } else {
                    for (int position = 0; position < filtered.size(); position++) {
                        if (filtered.get(position).source() == member) { grid.setSelection(position); break; }
                    }
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(56), dp(56));
            params.bottomMargin = dp(6);
            rail.addView(item, params);
        }
        watch(search, filter);
        refreshPicker[0] = () -> {
            sourceBar.removeAllViews();
            Button other = button(R.string.other_pack, view ->
                    choosePack(loaded -> {
                        if (!dialog.isShowing()) return;
                        custom[0] = loaded;
                        refreshPicker[0].run();
                        filter.accept(search.getText().toString());
                        grid.setSelection(0);
                    }, java.util.Set.copyOf(base.stream().map(member -> member.packageName).toList())));
            Button file = button(R.string.choose_icon_file, view -> {
                pendingIconApp = app.key();
                pendingIconChanged = changed;
                openFile(ICON_FILE, "image/*");
            });
            LinearLayout sources = row();
            LinearLayout.LayoutParams otherParams = new LinearLayout.LayoutParams(0, -2, 1);
            otherParams.rightMargin = dp(6);
            LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(0, -2, 1);
            fileParams.leftMargin = dp(6);
            sources.addView(other, otherParams);
            sources.addView(file, fileParams);
            if (custom[0] == null) { sourceBar.addView(sources, new LinearLayout.LayoutParams(-1, -2)); return; }
            Button back = button(base.size() > 1 ? R.string.template_packs : R.string.template_pack, view -> {
                custom[0] = null;
                refreshPicker[0].run();
                filter.accept(search.getText().toString());
                grid.setSelection(0);
            });
            sourceBar.addView(back, new LinearLayout.LayoutParams(-1, -2));
            sourceBar.addView(sources, new LinearLayout.LayoutParams(-1, -2));
        };
        refreshPicker[0].run();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            IconChoice choice = filtered.get(position);
            draft.overrides.put(app.key(), IconRepository.reference(choice.source().packageName, choice.icon().name()));
            changed.run(); dialog.dismiss();
        });
        filter.accept("");
        showFull(dialog);
    }

    record IconChoice(IconRepository.Pack source, IconRepository.Icon icon) {}

    private GradientDrawable packTab(boolean selected) {
        GradientDrawable shape = rounded(selected ? CONTROL : Color.TRANSPARENT, 14);
        if (selected) shape.setStroke(dp(2), ACCENT);
        return shape;
    }

    private IconRepository.Pack initialSource(IconRepository.App app) {
        if (pack.members.isEmpty()) return pack;
        // Open the catalogue that currently supplies this app, so its alternatives are visible first.
        String selected = pack.selected(app, draft.overrides);
        if (selected != null && !FileIcons.isFile(selected)) {
            String owner = IconRepository.sourcePackage(selected, pack.packageName);
            for (IconRepository.Pack member : pack.members) if (member.packageName.equals(owner)) return member;
        }
        return pack.members.get(0);
    }

    private void previewFileIcon(String appKey, Runnable changed, Bitmap image) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        fileIconDialog = dialog;
        LinearLayout content = column();
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        content.setBackground(rounded(PANEL, 24));
        content.addView(text(getString(R.string.file_icon_preview), 22, INK));
        content.addView(text(getString(R.string.file_icon_help), 14, MUTED));
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setPadding(dp(16), dp(16), dp(16), dp(16));
        preview.setBackground(rounded(CONTROL, 16));
        preview.setImageBitmap(image);
        preview.setContentDescription(getString(R.string.file_icon_preview));
        if (draft.overlayAll && !draft.overlay.isEmpty()) {
            String overlayReference = draft.overlay;
            String key = "file-preview:" + appKey + ":" + overlayReference;
            preview.setTag(key);
            Bitmap rendering = image.copy(Bitmap.Config.ARGB_8888, true);
            thumbnails.execute(() -> {
                try {
                    Bitmap bitmap = withPreviewOverlay(rendering, overlayReference);
                    runOnUiThread(() -> {
                        if (!isDestroyed() && dialog.isShowing()) preview.setImageBitmap(bitmap);
                        else bitmap.recycle();
                    });
                } catch (IOException | RuntimeException error) {
                    reportImageError(preview, key, appKey, error);
                }
            });
        }
        content.addView(preview, new LinearLayout.LayoutParams(-1, dp(192)));
        content.addView(button(R.string.use_icon_file, view -> {
            Bitmap saving = image.copy(Bitmap.Config.ARGB_8888, false);
            work(() -> {
                try { return FileIcons.save(this, saving); }
                finally { saving.recycle(); }
            }, reference -> {
                draft.overrides.put(appKey, reference);
                saveDraft();
                if (changed != null) changed.run();
                else if (editing) refresh();
                if (iconDialog != null) iconDialog.dismiss();
                dialog.dismiss();
                pendingIconApp = null;
                pendingIconChanged = null;
            });
        }));
        content.addView(button(R.string.cancel, view -> dialog.dismiss()));
        dialog.setContentView(content);
        dialog.setOnDismissListener(ignored -> {
            var displayed = preview.getDrawable();
            preview.setTag(null);
            preview.setImageDrawable(null);
            if (displayed instanceof android.graphics.drawable.BitmapDrawable drawable && drawable.getBitmap() != image)
                drawable.getBitmap().recycle();
            image.recycle();
            if (fileIconDialog == dialog) fileIconDialog = null;
        });
        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
    }

    private void bindSystemImage(ImageView view, IconRepository.App app) {
        String overlayReference = draft.overlay;
        String key = "system:" + app.key() + ":overlay:" + overlayReference;
        view.setTag(key);
        view.setImageResource(R.drawable.app_icon);
        Bitmap cached = images.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        thumbnails.execute(() -> {
            try {
                var icon = getPackageManager().getActivityIcon(app.component());
                if (overlayReference.isEmpty()) {
                    runOnUiThread(() -> { if (!isDestroyed() && key.equals(view.getTag())) view.setImageDrawable(icon); });
                } else {
                    Bitmap bitmap = withPreviewOverlay(IconShape.previewSource(icon, false), overlayReference);
                    images.put(key, bitmap);
                    runOnUiThread(() -> { if (!isDestroyed() && key.equals(view.getTag())) view.setImageBitmap(bitmap); });
                }
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // Apps that are not installed have no system icon; the placeholder stays.
            } catch (IOException | RuntimeException error) {
                reportImageError(view, key, app.key(), error);
            }
        });
    }

    private void bindImage(ImageView view, String name) {
        bindImage(view, pack, name);
    }

    private void bindImage(ImageView view, IconRepository.Pack current, String name) {
        String overlayReference = draft.overlayAll ? draft.overlay : "";
        String key = (name == null ? "" : IconRepository.reference(
                IconRepository.sourcePackage(name, current.packageName), IconRepository.resourceName(name)))
                + ":overlay:" + overlayReference;
        view.setTag(key);
        view.setImageResource(R.drawable.app_icon);
        if (name == null) return;
        Bitmap cached = images.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        thumbnails.execute(() -> {
            try {
                Bitmap bitmap = withPreviewOverlay(current.render(name, 128), overlayReference);
                images.put(key, bitmap);
                runOnUiThread(() -> { if (!isDestroyed() && key.equals(view.getTag())) view.setImageBitmap(bitmap); });
            } catch (IOException | RuntimeException error) {
                reportImageError(view, key, name, error);
            }
        });
    }

    private Bitmap withPreviewOverlay(Bitmap image, String reference) throws IOException {
        try {
            Bitmap overlay = IconOverlay.load(this, reference);
            try { IconOverlay.draw(image, overlay); }
            finally { if (overlay != null) overlay.recycle(); }
            return image;
        } catch (IOException | RuntimeException error) {
            image.recycle();
            throw error;
        }
    }

    private void reportImageError(ImageView view, String key, String name, Exception error) {
        Log.e("IconManager", "Не удалось показать " + name, error);
        runOnUiThread(() -> {
            if (!isDestroyed() && key.equals(view.getTag())) {
                view.setContentDescription(App.text(R.string.resource_error) + name);
                if (reportedImageErrors.add(key)) showError(error);
            }
        });
    }

    private void export() {
        if (title.getText().toString().isBlank() || pack == null || draft.tree.isEmpty()) {
            showError(new IOException(getString(R.string.required))); return;
        }
        String themeTitle = title.getText().toString().trim();
        if (themeTitle.length() > 100) { title.setError(App.text(R.string.title_length_error)); return; }
        Uri tree = Uri.parse(draft.tree);
        exportTo(themeTitle, java.time.LocalDateTime.now(), tree);
    }

    private void exportTo(String themeTitle, java.time.LocalDateTime createdAt, Uri tree) {
        saveDraft();
        Draft snapshot;
        try { snapshot = Draft.fromJson(draft.toJson()); }
        catch (org.json.JSONException error) { showError(error); return; }
        boolean asNew = saveAsNew.isChecked();
        boolean fullPack = draft.fullPack;
        IconShape shape = draft.shape;
        boolean clearFolderBackground = draft.clearFolderBackground;
        Map<String, String> overrides = Map.copyOf(draft.overrides);
        IconRepository.Pack currentPack = pack;
        String coverPath = draft.coverPath;
        work(getString(R.string.saving_theme), () -> {
            String outputTitle = ThemeEngine.availableThemeTitle(getContentResolver(), tree, themeTitle);
            String outputName = ThemeEngine.availableDatedFilename(getContentResolver(), tree, outputTitle, createdAt);
            apps = repository.apps();
            Bitmap cover = null;
            Bitmap overlay = IconOverlay.load(this, snapshot.overlay);
            try {
                if (coverPath.isEmpty()) cover = ThemeEngine.cover(currentPack, apps, overrides, snapshot.overlayAll ? overlay : null);
                else {
                    cover = BitmapFactory.decodeFile(new File(getFilesDir(), coverPath).getPath());
                    if (cover == null) throw new IOException(App.text(R.string.cover_read_error));
                }
                ThemeEngine.Result result = ThemeEngine.export(this, outputTitle, outputName, fullPack, shape, clearFolderBackground, currentPack, apps, overrides,
                        cover, tree, null, snapshot.wallpaperPath.isEmpty() ? null
                                : new File(getFilesDir(), snapshot.wallpaperPath), overlay, snapshot.overlayAll, (done, total) -> {
                            if (done == 1 || done == total || done % 10 == 0) runOnUiThread(() -> {
                                if (!isDestroyed()) {
                                    String message = done == total ? getString(R.string.export_finishing)
                                            : getString(R.string.export_progress, done, total);
                                    if (progressDialog != null) progressMessage.setText(message);
                                }
                            });
                        });
                try {
                    return new SavedExport(result, templates.save(snapshot, currentPack.label, asNew), outputTitle);
                } catch (Exception error) {
                    throw new IOException(App.text(R.string.saved_theme_prefix) + outputName
                            + App.text(R.string.template_save_partial) + error.getMessage(), error);
                }
            } finally { if (cover != null) cover.recycle(); if (overlay != null) overlay.recycle(); }
        }, saved -> {
            draft.templateId = saved.template().id();
            saveDraft();
            markDraftSaved();
            saveAsNew.setChecked(false);
            saveAsNew.setVisibility(View.VISIBLE);
            templateHelp.setText(R.string.template_update_help);
            AlertDialog dialog = showExportResult(saved.theme());
            dialog.setMessage(getString(saved.theme().clearFolderBackground() ? R.string.export_done_full_theme
                    : R.string.export_done, "Honor/Themes/" + saved.theme().filename())
                    + "\n\n" + getString(R.string.export_display_title, saved.title())
                    + "\n\n" + getString(R.string.template_saved, saved.template().name()));
        });
    }

    AlertDialog showExportResult(ThemeEngine.Result result) {
        exportDialog = new AlertDialog.Builder(this).setTitle(R.string.export_success)
                .setMessage(getString(result.clearFolderBackground() ? R.string.export_done_full_theme
                        : R.string.export_done, "Honor/Themes/" + result.filename()))
                .setPositiveButton(R.string.open_themes, (dialog, which) -> openThemes()).show();
        return exportDialog;
    }

    static Intent themesIntent(android.content.pm.PackageManager pm) throws IOException {
        for (String name : List.of("com.hihonor.android.thememanager", "com.huawei.android.thememanager")) {
            Intent intent = pm.getLaunchIntentForPackage(name);
            if (intent != null) return intent;
        }
        throw new IOException(App.text(R.string.themes_not_found));
    }

    private void openThemes() {
        try { startActivity(themesIntent(getPackageManager())); }
        catch (IOException | android.content.ActivityNotFoundException | SecurityException error) { showError(error); }
    }

    private <T> void work(Callable<T> action, Consumer<T> success) {
        work(action, success, () -> {});
    }

    private <T> void work(Callable<T> action, Consumer<T> success, Runnable failure) {
        work(getString(R.string.loading), action, success, failure);
    }

    private <T> void work(String message, Callable<T> action, Consumer<T> success) {
        work(message, action, success, () -> {});
    }

    private <T> void work(String message, Callable<T> action, Consumer<T> success, Runnable failure) {
        work(message, action, success, failure, false);
    }

    /** With {@code untilDrawn}, progress stays visible until the UI built by {@code success} reaches the screen. */
    private <T> void work(String message, Callable<T> action, Consumer<T> success, Runnable failure, boolean untilDrawn) {
        if (busy) { showError(new IOException(getString(R.string.busy))); return; }
        busy = true;
        enabled(root, false);
        showOperationProgress(message);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            try {
                T result = action.call();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (!untilDrawn) { finishWork(); success.accept(result); return; }
                    try { success.accept(result); }
                    finally {
                        root.getViewTreeObserver().registerFrameCommitCallback(() -> { if (!isDestroyed()) finishWork(); });
                        root.invalidate();
                    }
                });
            } catch (Exception error) {
                Log.e("IconManager", "Operation failed", error);
                runOnUiThread(() -> { if (!isDestroyed()) { finishWork(); failure.run(); showError(error); } });
            }
        });
    }

    private void finishWork() {
        if (progressDialog != null) { progressDialog.dismiss(); progressDialog = null; }
        busy = false; enabled(root, true);
        updateExportEnabled();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showOperationProgress(String message) {
        // Pack selection already has its own full-screen skeleton.
        if (packDialog != null && packDialog.isShowing()) return;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        LinearLayout content = column();
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(28), dp(28), dp(28), dp(28));
        content.setBackground(rounded(PANEL, 28));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        content.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));
        progressMessage = text(message, 17, INK);
        progressMessage.setGravity(Gravity.CENTER);
        progressMessage.setPadding(0, dp(20), 0, 0);
        content.addView(progressMessage);
        dialog.setContentView(content);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dialog.getWindow().setDimAmount(.65f);
        dialog.getWindow().setGravity(Gravity.CENTER);
        dialog.show();
        dialog.getWindow().setLayout(Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(48)), -2);
        progressDialog = dialog;
    }
    private static void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) enabled(group.getChildAt(i), enabled);
    }
    private void saveDraft() {
        try { draft.save(this); }
        catch (org.json.JSONException error) { Log.e("IconManager", "Draft save failed", error); showError(error); }
    }
    private void markDraftSaved() {
        try { draft.markSaved(this); }
        catch (Exception error) { Log.w("IconManager", "Draft saved-state update failed", error); }
    }
    private void showError(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        for (Throwable extra : error.getSuppressed()) message += "\n" + extra.getMessage();
        errorDialog = new AlertDialog.Builder(this).setTitle(R.string.error_title).setMessage(message).show();
    }
    private Dialog fullDialog() { return new Dialog(this, R.style.AppTheme); }
    private LinearLayout scrollingDialogLayout(Dialog dialog, String heading) {
        LinearLayout layout = dialogLayout(dialog, heading);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        scroll.addView(content);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return content;
    }

    private LinearLayout dialogLayout(Dialog dialog, String heading) {
        LinearLayout layout = column(); layout.setPadding(dp(16), dp(12), dp(16), dp(12)); layout.setBackgroundColor(BG);
        LinearLayout bar = row();
        TextView label = text(heading, 24, INK);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setMinHeight(dp(56));
        label.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(bar);
        dialog.setContentView(layout);
        return layout;
    }
    private void showFull(Dialog dialog) {
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        View decor = dialog.getWindow().getDecorView();
        decor.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            int margin = Math.max(0, (view.getWidth() - safe.left - safe.right - dp(880)) / 2);
            view.setPadding(margin + safe.left, safe.top, margin + safe.right, safe.bottom); return insets;
        });
        decor.requestApplyInsets();
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        view.setPadding(0, dp(6), 0, dp(6)); return view;
    }
    private EditText edit(int hint, String value) {
        EditText view = new EditText(this); view.setHint(hint); view.setText(value);
        view.setTextSize(16); view.setTextColor(INK); view.setHintTextColor(MUTED);
        view.setMinHeight(dp(56));
        view.setPadding(dp(16), dp(12), dp(16), dp(12));
        StateListDrawable background = new StateListDrawable();
        GradientDrawable focused = rounded(CONTROL, 16);
        focused.setStroke(dp(2), ACCENT);
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[]{}, rounded(CONTROL, 16));
        view.setBackground(background);
        return view;
    }
    private Button button(int label, View.OnClickListener action) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextColor(ACCENT_TEXT);
        view.setTextSize(14);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setMinimumHeight(0);
        view.setMinHeight(dp(48));
        view.setPadding(dp(16), dp(6), dp(16), dp(6));
        view.setBackground(buttonSurface(CONTROL, 24));
        view.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4); params.bottomMargin = dp(4);
        view.setLayoutParams(params);
        return view;
    }
    private LinearLayout card(LinearLayout parent, int heading) {
        LinearLayout panel = column(); panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(rounded(PANEL, 24));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(16); params.bottomMargin = dp(2);
        parent.addView(panel, params);
        if (heading != 0) {
            TextView label = text(getString(heading), 18, INK);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); panel.addView(label);
        }
        return panel;
    }

    private final class OptionalSection {
        final LinearLayout content = column();
        final Button toggle;
        private boolean populated;

        OptionalSection(LinearLayout parent, int heading, boolean populated) {
            this.populated = populated;
            LinearLayout panel = card(parent, 0);
            toggle = button(heading, view -> setExpanded(content.getVisibility() != View.VISIBLE));
            toggle.setTextColor(INK);
            toggle.setTextSize(18);
            toggle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            toggle.setPadding(0, 0, 0, 0);
            toggle.setCompoundDrawablePadding(dp(12));
            toggle.setBackground(buttonSurface(PANEL, 16));
            toggle.setStateListAnimator(null);
            toggle.setElevation(0);
            panel.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
            panel.addView(content, new LinearLayout.LayoutParams(-1, -2));
            setExpanded(populated);
        }

        void setExpanded(boolean expanded) {
            content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            var arrow = getDrawable(expanded ? R.drawable.ic_collapse : R.drawable.ic_expand).mutate();
            arrow.setTint(MUTED);
            toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null);
            toggle.setStateDescription(getString(expanded ? R.string.section_expanded : R.string.section_collapsed));
        }

        void update(boolean populated) {
            if (populated && !this.populated) setExpanded(true);
            this.populated = populated;
        }
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private RippleDrawable buttonSurface(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(color == ACCENT ? 0x33000000 : 0x33FFC107),
                rounded(color, radius), rounded(Color.WHITE, radius));
    }
    private void watch(EditText edit, Consumer<String> changed) {
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changed.accept(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pendingIconApp", pendingIconApp);
        state.putBoolean("editing", editing);
    }
    @Override protected void onStop() { saveDraft(); super.onStop(); }
    @Override protected void onDestroy() {
        if (packDialog != null) packDialog.dismiss();
        if (cropDialog != null) cropDialog.dismiss();
        if (iconDialog != null) iconDialog.dismiss();
        if (exportDialog != null) exportDialog.dismiss();
        if (errorDialog != null) errorDialog.dismiss();
        if (deleteDialog != null) deleteDialog.dismiss();
        if (progressDialog != null) progressDialog.dismiss();
        if (fileIconDialog != null) fileIconDialog.dismiss();
        if (settingsDialog != null) settingsDialog.dismiss();
        if (overlayDialog != null) overlayDialog.dismiss();
        worker.shutdown(); thumbnails.shutdown(); super.onDestroy();
    }
}
