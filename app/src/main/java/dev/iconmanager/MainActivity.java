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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int COVER = 11, DIRECTORY = 12, WALLPAPER = 13, ICON_FILE = 14, OVERLAY_FILE = 15,
            BACKGROUND_FILE = 16, MASK_FILE = 17;
    private int BG, PANEL, MUTED, CONTROL, INK, ACCENT_TEXT;
    private static final int ACCENT = Color.rgb(255, 193, 7);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor thumbnails = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>());
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
    private AlertDialog exportDialog, errorDialog, deleteDialog, exportSizeDialog;
    private Dialog progressDialog;
    private TextView progressMessage;
    private String pendingIconApp;
    private Runnable pendingIconChanged;
    private Dialog fileIconDialog;
    private Dialog helpDialog;
    private AlertDialog filterTypeDialog;
    private Dialog settingsDialog, overlayDialog, shapeDialog, backgroundDialog;
    private java.util.function.Consumer<MaskSelection> pendingShapeMaskImport;
    private RadioGroup exportScope;
    private ImageView stylePreview;
    private TextView iconSizeInfo;
    private Button shapeButton;
    private Button removeOverlay, removeBackground, backgroundSettings;
    private RadioGroup overlayScope, backgroundScope;
    private OptionalSection coverSection, wallpaperSection, overlaySection, backgroundSection;
    private boolean busy;
    private boolean editing;
    private TemplateStore templates;
    private CheckBox saveAsNew;
    private record SavedExport(ThemeEngine.Result theme, TemplateStore.Entry template, String title) {}
    private record MaskSelection(String reference, Bitmap bitmap) {}
    private final java.util.Set<String> reportedImageErrors = new java.util.HashSet<>();
    private final List<View> editorPages = new ArrayList<>();
    private final List<Button> editorTabs = new ArrayList<>();
    private final List<Button> styleTabs = new ArrayList<>();
    private final List<LinearLayout> styleSections = new ArrayList<>();
    private int editorTab;
    private int styleTab = 2;
    private Runnable pendingStylePreview;

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
        if (savedInstanceState != null) {
            pendingIconApp = savedInstanceState.getString("pendingIconApp");
            editorTab = savedInstanceState.getInt("editorTab");
            styleTab = savedInstanceState.getInt("styleTab", 2);
        }
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
            else if (editing) {
                saveDraft();
                if (editorTab == 0) showTemplates();
                else showEditorTab(0);
            } else finish();
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

    private android.widget.ImageButton helpButton(int title, int... texts) {
        return helpButton(getString(title), texts);
    }

    private android.widget.ImageButton helpButton(String title, int... texts) {
        android.widget.ImageButton button = iconButton(R.drawable.ic_help, R.string.help, view -> showHelp(title, texts));
        button.setTag("help-" + getResources().getResourceEntryName(texts[0]));
        button.setContentDescription(getString(R.string.help_about, title));
        button.setTooltipText(getString(R.string.help));
        button.setImageTintList(ColorStateList.valueOf(MUTED));
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        button.setBackground(buttonSurface(Color.TRANSPARENT, 24));
        return button;
    }

    /** Places a help button after a heading view while letting the heading keep its full width. */
    private LinearLayout withHelp(View heading, String title, int... texts) {
        LinearLayout line = row();
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, -2);
        lineParams.bottomMargin = dp(8);
        line.setLayoutParams(lineParams);
        line.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMarginStart(dp(8));
        line.addView(helpButton(title, texts), params);
        return line;
    }

    private void showHelp(String title, int... texts) {
        if (helpDialog != null) helpDialog.dismiss();
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        helpDialog = dialog;
        LinearLayout content = column();
        content.setTag("help-dialog");
        content.setPadding(dp(24), dp(20), dp(24), dp(16));
        content.setBackground(rounded(PANEL, 28));
        LinearLayout heading = row();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_help);
        icon.setImageTintList(ColorStateList.valueOf(ACCENT_TEXT));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView label = text(title, 22, INK);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setAccessibilityHeading(true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(12));
        heading.addView(label, labelParams);
        content.addView(heading);
        ScrollView scroll = new ScrollView(this);
        LinearLayout paragraphs = column();
        for (int text : texts) {
            TextView paragraph = text(getString(text), 16, INK);
            paragraph.setTag("help-text");
            paragraph.setLineSpacing(0, 1.25f);
            paragraph.setPadding(0, dp(8), 0, dp(8));
            paragraphs.addView(paragraph);
        }
        scroll.addView(paragraphs);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, -2);
        scrollParams.topMargin = dp(8);
        content.addView(scroll, scrollParams);
        Button close = button(R.string.close, view -> dialog.dismiss());
        close.setTextColor(Color.BLACK);
        close.setBackground(buttonSurface(ACCENT, 24));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, -2);
        closeParams.topMargin = dp(12);
        content.addView(close, closeParams);
        dialog.setContentView(content);
        dialog.setOnDismissListener(ignored -> { if (helpDialog == dialog) helpDialog = null; });
        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        int width = Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(32));
        int height = Math.round(getResources().getDisplayMetrics().heightPixels * 0.8f);
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        dialog.getWindow().setLayout(width, content.getMeasuredHeight() > height ? height : -2);
    }

    /** Wraps a slider with ±1 buttons; {@code stepped} receives values set by those buttons. */
    private LinearLayout steppedSlider(SeekBar slider, java.util.function.IntConsumer stepped) {
        LinearLayout line = row();
        String tag = String.valueOf(slider.getTag());
        for (int direction : new int[]{-1, 1}) {
            android.widget.ImageButton arrow = iconButton(direction < 0 ? R.drawable.ic_chevron_left
                    : R.drawable.ic_chevron_right, direction < 0 ? R.string.decrease : R.string.increase, view -> {
                int value = Math.max(slider.getMin(), Math.min(slider.getMax(), slider.getProgress() + direction));
                if (value == slider.getProgress()) return;
                slider.setProgress(value);
                stepped.accept(value);
            });
            arrow.setTag(tag + (direction < 0 ? "-decrease" : "-increase"));
            arrow.setPadding(dp(10), dp(10), dp(10), dp(10));
            if (direction > 0) line.addView(slider, new LinearLayout.LayoutParams(0, dp(48), 1));
            line.addView(arrow, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        return line;
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
        editorTab = 0;
        prepareScreen();
        TextView heading = text(getString(R.string.templates), 32, INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMinHeight(dp(72));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout header = row();
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams templatesHelpParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        templatesHelpParams.setMarginEnd(dp(12));
        header.addView(helpButton(R.string.templates, R.string.templates_help), templatesHelpParams);
        header.addView(settingsButton(), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
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
        editorPages.clear();
        editorTabs.clear();
        styleTabs.clear();
        styleSections.clear();
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

        FrameLayout pages = new FrameLayout(this);
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout baseBody = editorPage(pages);
        styleEditorPage(pages);
        LinearLayout appsBody = editorPage(pages);
        LinearLayout exportBody = editorPage(pages);

        baseBody.addView(withHelp(text(getString(R.string.tagline), 28, INK), getString(R.string.tagline), R.string.intro));
        LinearLayout identity = card(baseBody, 0);
        title = edit(R.string.title_hint, draft.title);
        title.setSingleLine(true);
        TextView titleLabel = text(getString(R.string.theme_title), 14, MUTED);
        title.setId(View.generateViewId());
        titleLabel.setLabelFor(title.getId());
        identity.addView(withHelp(titleLabel, getString(R.string.theme_title), R.string.filename_help));
        identity.addView(title);
        watch(title, value -> { draft.title = value; saveDraft(); updateExportEnabled(); });
        LinearLayout packCard = card(baseBody, R.string.pack, R.string.pack_priority_help);
        packList = column();
        packCard.addView(packList);
        packInfo = text("", 13, MUTED);
        packInfo.setPadding(0, dp(4), 0, dp(4));
        packCard.addView(packInfo);
        addPackButton = button(R.string.add_pack, view -> choosePack());
        packCard.addView(addPackButton);

        coverSection = new OptionalSection(baseBody, R.string.cover, !draft.coverPath.isEmpty());
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
        wallpaperSection = new OptionalSection(baseBody, R.string.wallpaper, !draft.wallpaperPath.isEmpty(),
                R.string.wallpaper_help);
        LinearLayout wallpaper = wallpaperSection.content;
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
        backgroundSection = new OptionalSection(styleSections.get(0), R.string.background_title, !draft.background.isEmpty(), false);
        LinearLayout background = backgroundSection.content;
        LinearLayout backgroundButtons = row();
        backgroundButtons.addView(button(R.string.choose_background, view -> openFile(BACKGROUND_FILE, "image/png")),
                new LinearLayout.LayoutParams(0, -2, 1));
        removeBackground = button(R.string.remove_background, view -> {
            draft.background = ""; draft.backgroundAll = false; draft.backgroundScale = 100; draft.backgroundClip = true;
            saveDraft(); refresh();
        });
        LinearLayout.LayoutParams removeBackgroundParams = new LinearLayout.LayoutParams(0, -2, 1);
        removeBackgroundParams.leftMargin = dp(6);
        backgroundButtons.addView(removeBackground, removeBackgroundParams);
        background.addView(withHelp(backgroundButtons, getString(R.string.background_title), R.string.background_help));
        backgroundSettings = button(R.string.background_settings, view -> {
            String reference = draft.background;
            work(() -> {
                byte[] bytes = FileIcons.bytes(this, reference);
                Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (image == null) throw new IOException(getString(R.string.background_invalid));
                return image;
            }, this::previewBackground);
        });
        backgroundSettings.setTag("background-settings");
        addStyleLayerControls(background, false);
        backgroundScope = new RadioGroup(this);
        for (int label : new int[]{R.string.overlay_fallback, R.string.overlay_all}) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId()); option.setText(label);
            backgroundScope.addView(option);
        }
        backgroundScope.check(backgroundScope.getChildAt(draft.backgroundAll ? 1 : 0).getId());
        backgroundScope.setOnCheckedChangeListener((group, id) -> {
            draft.backgroundAll = id == backgroundScope.getChildAt(1).getId(); saveDraft(); refresh();
        });
        background.addView(backgroundScope);
        overlaySection = new OptionalSection(styleSections.get(1), R.string.overlay_title, !draft.overlay.isEmpty(), false);
        LinearLayout overlay = overlaySection.content;
        LinearLayout overlayButtons = row();
        overlayButtons.addView(button(R.string.choose_overlay, view -> openFile(OVERLAY_FILE, "image/png")),
                new LinearLayout.LayoutParams(0, -2, 1));
        removeOverlay = button(R.string.remove_overlay, view -> {
            draft.overlay = ""; draft.overlayAll = false; draft.overlayScale = 100; draft.overlayClip = true;
            saveDraft(); refresh();
        });
        LinearLayout.LayoutParams removeOverlayParams = new LinearLayout.LayoutParams(0, -2, 1);
        removeOverlayParams.leftMargin = dp(6);
        overlayButtons.addView(removeOverlay, removeOverlayParams);
        overlay.addView(withHelp(overlayButtons, getString(R.string.overlay_title), R.string.overlay_help));
        addStyleLayerControls(overlay, true);
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
        packCard = card(styleSections.get(2), 0);
        iconSizeInfo = text("", 13, MUTED);
        packCard.addView(iconSizeInfo);
        packCard.addView(withHelp(text(getString(R.string.fallback_shape), 16, INK),
                getString(R.string.fallback_shape), R.string.fallback_shape_help));
        shapeButton = button(R.string.choose_shape, view -> showShapePicker());
        addInlineShapeControls(packCard);
        packCard.addView(createScaleControl(draft.iconScale, "style-icon-scale", R.string.icon_scale,
                R.string.icon_scale_help, value -> { draft.iconScale = value; styleChanged(); }));
        LinearLayout filters = card(styleSections.get(3), 0);
        filters.addView(withHelp(text(getString(R.string.filters_examples), 14, INK),
                getString(R.string.filters_title), R.string.filters_help));
        addFilterControls(filters, draft.filters, () -> { styleChanged(); refreshAppInfo(); });
        CheckBox folderBackground = new CheckBox(this);
        folderBackground.setText(R.string.folder_background);
        folderBackground.setTextColor(INK);
        folderBackground.setChecked(draft.clearFolderBackground);
        folderBackground.setOnCheckedChangeListener((view, checked) -> {
            draft.clearFolderBackground = checked; saveDraft();
        });
        packCard.addView(withHelp(folderBackground, getString(R.string.folder_background),
                R.string.folder_background_help));

        LinearLayout appCard = card(appsBody, R.string.apps, R.string.apps_help);
        appInfo = text("", 14, ACCENT_TEXT); appCard.addView(appInfo);
        appCard.addView(button(R.string.apps, view -> {
            if (pack == null) showError(new IOException(getString(R.string.no_pack)));
            else showApps();
        }));

        LinearLayout scopeCard = card(exportBody, R.string.export_scope, R.string.scope_help);
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
        scopeCard.addView(exportScope);
        LinearLayout destination = card(exportBody, R.string.destination, R.string.destination_help);
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
        exportBody.addView(templateHelp);
        saveAsNew = new CheckBox(this);
        saveAsNew.setText(R.string.save_as_new_template);
        saveAsNew.setVisibility(draft.templateId.isEmpty() ? View.GONE : View.VISIBLE);
        exportBody.addView(saveAsNew);

        addDraftStatus();
        Button export = button(R.string.export, view -> export());
        exportButton = export;
        export.setTextColor(Color.BLACK); export.setBackground(buttonSurface(ACCENT, 28));
        export.setMinHeight(dp(56));
        root.addView(export, new LinearLayout.LayoutParams(-1, -2));
        addEditorTabs();
        showEditorTab(editorTab);
        refresh();
    }

    private LinearLayout editorPage(FrameLayout parent) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = column();
        body.setPadding(0, 0, 0, dp(8));
        scroll.addView(body);
        parent.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        editorPages.add(scroll);
        return body;
    }

    private void styleChanged() {
        saveDraft();
        images.evictAll();
        syncStyleControls();
        refreshStylePreview();
    }

    private void syncStyleControls() {
        int[] values = {draft.backgroundScale, draft.overlayScale, draft.iconScale, draft.maskScale};
        String[] tags = {"style-background-scale", "style-overlay-scale", "style-icon-scale", "style-mask-scale"};
        for (int i = 0; i < tags.length; i++) {
            SeekBar slider = root.findViewWithTag(tags[i]);
            if (slider != null) {
                slider.setProgress(values[i]);
                if (i == 3) enabled((View) slider.getParent(), draft.shape == IconShape.FILE);
            }
        }
        for (IconShape shape : IconShape.values()) {
            View option = root.findViewWithTag(shape);
            if (option != null) {
                boolean selected = draft.shape == shape;
                option.setSelected(selected);
                option.setStateDescription(getString(selected ? R.string.option_selected : R.string.option_not_selected));
                GradientDrawable background = rounded(selected ? CONTROL : Color.TRANSPARENT, 18);
                if (selected) background.setStroke(dp(2), ACCENT);
                option.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFC107),
                        background, rounded(Color.WHITE, 18)));
                option.findViewWithTag("shape-check").setVisibility(selected ? View.VISIBLE : View.GONE);
            }
        }
        CheckBox background = root.findViewWithTag("style-background-clip");
        CheckBox overlay = root.findViewWithTag("style-overlay-clip");
        if (background != null) background.setChecked(draft.backgroundClip);
        if (overlay != null) overlay.setChecked(draft.overlayClip);
    }

    private void addStyleLayerControls(LinearLayout content, boolean overlay) {
        content.addView(createScaleControl(overlay ? draft.overlayScale : draft.backgroundScale,
                overlay ? "style-overlay-scale" : "style-background-scale",
                overlay ? R.string.overlay_scale : R.string.background_scale,
                overlay ? R.string.overlay_scale_help : R.string.background_scale_help,
                value -> {
                    if (overlay) draft.overlayScale = value; else draft.backgroundScale = value;
                    styleChanged();
                }));
        CheckBox clip = new CheckBox(this);
        clip.setTag(overlay ? "style-overlay-clip" : "style-background-clip");
        clip.setText(overlay ? R.string.overlay_clip : R.string.background_clip);
        clip.setChecked(overlay ? draft.overlayClip : draft.backgroundClip);
        clip.setOnCheckedChangeListener((view, checked) -> {
            if (overlay) draft.overlayClip = checked; else draft.backgroundClip = checked;
            styleChanged();
        });
        content.addView(withHelp(clip, getString(overlay ? R.string.overlay_clip : R.string.background_clip),
                overlay ? R.string.overlay_clip_help : R.string.background_clip_help));
    }

    private void addInlineShapeControls(LinearLayout content) {
        LinearLayout tabs = row();
        tabs.setBackground(rounded(CONTROL, 24));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        content.addView(tabs);
        LinearLayout presets = column();
        presets.setTag("style-shape-presets");
        LinearLayout editor = column();
        editor.setTag("style-shape-editor");
        LinearLayout file = column();
        file.setTag("style-shape-file");
        LinearLayout[] pages = {presets, editor, file};
        int[] labels = {R.string.shape_tab_presets, R.string.shape_tab_editor, R.string.shape_tab_file};
        String[] names = {"presets", "editor", "file"};
        Button[] buttons = new Button[3];
        IconShape[] preset = {draft.shape == IconShape.CUSTOM || draft.shape == IconShape.FILE
                ? IconShape.ROUNDED_SQUARE : draft.shape};
        java.util.function.IntConsumer showTab = selected -> {
            for (int i = 0; i < pages.length; i++) {
                boolean active = i == selected;
                pages[i].setVisibility(active ? View.VISIBLE : View.GONE);
                buttons[i].setSelected(active);
                buttons[i].setBackground(buttonSurface(active ? ACCENT : CONTROL, 20));
                buttons[i].setTextColor(active ? Color.BLACK : ACCENT_TEXT);
                buttons[i].setStateDescription(getString(active ? R.string.option_selected : R.string.option_not_selected));
            }
        };
        for (int i = 0; i < pages.length; i++) {
            int index = i;
            buttons[i] = button(labels[i], view -> {
                showTab.accept(index);
                if (index != 2 || !draft.shapeFile.isEmpty()) {
                    draft.shape = index == 0 ? preset[0] : index == 1 ? IconShape.CUSTOM : IconShape.FILE;
                    styleChanged();
                }
            });
            buttons[i].setTag("style-shape-tab-" + names[i]);
            buttons[i].setStateListAnimator(null);
            buttons[i].setElevation(0);
            buttons[i].setTranslationZ(0);
            tabs.addView(buttons[i], new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(pages[i]);
        }
        LinearLayout row = null;
        int position = 0;
        for (IconShape shape : List.of(IconShape.ROUNDED_SQUARE, IconShape.SQUIRCLE, IconShape.SQUARE, IconShape.CIRCLE,
                IconShape.HEART, IconShape.STAR, IconShape.HEXAGON, IconShape.PENTAGON, IconShape.TEARDROP, IconShape.NONE)) {
            if (row == null || row.getChildCount() == 4) {
                row = row(); row.setGravity(Gravity.TOP); presets.addView(row);
            }
            LinearLayout option = shapeChoice(shape, position++);
            option.setOnClickListener(view -> {
                preset[0] = shape;
                draft.shape = shape; draft.shapeFile = ""; draft.maskScale = 100;
                styleChanged();
            });
            row.addView(option, new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (row != null) while (row.getChildCount() < 4)
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        editor.addView(withHelp(text(getString(R.string.shape_editor), 16, INK),
                getString(R.string.shape_editor), R.string.shape_editor_help));
        for (int i = 0; i < 4; i++) {
            int index = i;
            int label = new int[]{R.string.corner_top_left, R.string.corner_top_right,
                    R.string.corner_bottom_right, R.string.corner_bottom_left}[i];
            TextView caption = text(getString(label, draft.corners[i]), 14, INK);
            editor.addView(caption);
            SeekBar corner = new SeekBar(this);
            corner.setTag("style-corner-" + i);
            corner.setMax(IconShape.MAX_CORNER);
            corner.setProgress(draft.corners[i]);
            java.util.function.IntConsumer changed = value -> {
                draft.corners[index] = value;
                draft.shape = IconShape.CUSTOM; draft.shapeFile = ""; draft.maskScale = 100;
                caption.setText(getString(label, value));
                styleChanged();
            };
            corner.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (fromUser) changed.accept(value);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            editor.addView(steppedSlider(corner, changed));
        }
        file.addView(withHelp(text(getString(R.string.shape_file_title), 16, INK),
                getString(R.string.shape_file_title), R.string.shape_file_help));
        TextView fileStatus = text(getString(draft.shapeFile.isEmpty()
                ? R.string.shape_file_none : R.string.shape_file_selected), 14, INK);
        file.addView(fileStatus);
        file.addView(button(R.string.choose_shape_file, view -> {
            pendingShapeMaskImport = selection -> {
                draft.shape = IconShape.FILE;
                draft.shapeFile = selection.reference();
                selection.bitmap().recycle();
                fileStatus.setText(R.string.shape_file_selected);
                styleChanged();
            };
            openFile(MASK_FILE, "image/png");
        }));
        file.addView(createScaleControl(draft.maskScale, "style-mask-scale", R.string.mask_scale,
                R.string.mask_scale_help, value -> {
                    draft.maskScale = value; styleChanged();
                }));
        showTab.accept(draft.shape == IconShape.CUSTOM ? 1 : draft.shape == IconShape.FILE ? 2 : 0);
    }

    private LinearLayout shapeChoice(IconShape shape, int position) {
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
        silhouette.setImageBitmap(icon);
        if (shape != IconShape.NONE) silhouette.setImageTintList(ColorStateList.valueOf(
                position < 4 ? 0xFFFF9B91 : 0xFFA3AEFF));
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
        return choice;
    }

    private void styleEditorPage(FrameLayout parent) {
        LinearLayout page = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                boolean wide = getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                setOrientation(wide ? HORIZONTAL : VERTICAL);
                if (getChildCount() == 2) {
                    if (getChildAt(0).getLayoutParams().width != (wide ? 0 : -1)) {
                        getChildAt(0).setLayoutParams(wide ? new LinearLayout.LayoutParams(0, -1, 1)
                                : new LinearLayout.LayoutParams(-1, -2));
                        getChildAt(1).setLayoutParams(wide ? new LinearLayout.LayoutParams(0, -1, 2)
                                : new LinearLayout.LayoutParams(-1, 0, 1));
                    }
                    View tabs = findViewWithTag("style-tabs");
                    ((LinearLayout.LayoutParams) tabs.getLayoutParams()).topMargin = wide ? 0 : dp(12);
                    View scroll = findViewWithTag("style-scroll");
                    ((LinearLayout.LayoutParams) scroll.getLayoutParams()).topMargin = wide ? 0 : dp(12);
                    for (Button tab : styleTabs) {
                        int height = dp(wide ? 48 : 56);
                        if (tab.getMinHeight() != height) tab.setMinHeight(height);
                    }
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        page.setOrientation(LinearLayout.VERTICAL);
        FrameLayout stage = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (page.getOrientation() == LinearLayout.HORIZONTAL) {
                    super.onMeasure(widthSpec, heightSpec);
                    return;
                }
                int height = Math.round(View.MeasureSpec.getSize(widthSpec)
                        * (float) ShapePreview.HEIGHT / ShapePreview.WIDTH);
                if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) {
                    int available = View.MeasureSpec.getSize(heightSpec);
                    height = Math.min(height, available / 3);
                }
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            }
        };
        stage.setTag("style-stage");
        stage.setBackground(rounded(CONTROL, 20));
        stage.setClipToOutline(true);
        stylePreview = new ImageView(this);
        stylePreview.setTag("style-preview");
        stylePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stylePreview.setContentDescription(getString(R.string.style_preview));
        stage.addView(stylePreview, new FrameLayout.LayoutParams(-1, -1));
        stage.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                refreshStylePreview();
        });
        page.addView(stage, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout settings = column();
        settings.setTag("style-controls");
        page.addView(settings, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout tabs = row();
        tabs.setTag("style-tabs");
        tabs.setBackground(rounded(CONTROL, 24));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        int[] labels = {R.string.background_title, R.string.overlay_title, R.string.theme_options, R.string.filters};
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            Button tab = button(labels[i], view -> showStyleTab(index));
            tab.setTag("style-tab-" + i);
            tab.setTextSize(12);
            tab.setMaxLines(2);
            tab.setGravity(Gravity.CENTER);
            tab.setMinHeight(dp(56));
            tab.setPadding(dp(4), dp(4), dp(4), dp(4));
            tab.setStateListAnimator(null);
            tab.setElevation(0);
            tab.setTranslationZ(0);
            styleTabs.add(tab);
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, -2);
        tabsParams.topMargin = dp(12);
        settings.addView(tabs, tabsParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setTag("style-scroll");
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = column();
        for (int i = 0; i < labels.length; i++) {
            LinearLayout section = column();
            section.setTag("style-section-" + i);
            section.setPadding(0, 0, 0, dp(8));
            styleSections.add(section);
            content.addView(section, new LinearLayout.LayoutParams(-1, -2));
        }
        scroll.addView(content);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = dp(12);
        settings.addView(scroll, scrollParams);
        parent.addView(page, new FrameLayout.LayoutParams(-1, -1));
        editorPages.add(page);
        showStyleTab(styleTab);
    }

    private void showStyleTab(int selected) {
        boolean wasFilters = styleTab == 3;
        styleTab = Math.max(0, Math.min(styleTabs.size() - 1, selected));
        for (int i = 0; i < styleSections.size(); i++) {
            styleSections.get(i).setVisibility(i == styleTab ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < styleTabs.size(); i++) {
            Button tab = styleTabs.get(i);
            boolean active = i == styleTab;
            tab.setSelected(active);
            tab.setBackground(buttonSurface(active ? ACCENT : CONTROL, 20));
            tab.setTextColor(active ? Color.BLACK : ACCENT_TEXT);
            tab.setStateDescription(getString(active ? R.string.option_selected : R.string.option_not_selected));
        }
        if (wasFilters != (styleTab == 3) && stylePreview != null) refreshStylePreview();
    }

    private void addEditorTabs() {
        LinearLayout bar = row();
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackground(rounded(PANEL, 24));
        int[] labels = {R.string.tab_base, R.string.tab_style, R.string.tab_apps, R.string.tab_export};
        int[] icons = {R.drawable.ic_tab_base, R.drawable.ic_tab_style, R.drawable.ic_tab_apps, R.drawable.ic_tab_export};
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            Button tab = button(labels[i], view -> showEditorTab(index));
            tab.setTag("editor-tab-" + i);
            tab.setTextSize(12);
            tab.setGravity(Gravity.CENTER);
            tab.setMinHeight(dp(64));
            tab.setPadding(dp(4), dp(6), dp(4), dp(6));
            tab.setCompoundDrawablePadding(dp(3));
            tab.setCompoundDrawablesWithIntrinsicBounds(null, getDrawable(icons[i]), null, null);
            editorTabs.add(tab);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(72), 1);
            if (i > 0) params.leftMargin = dp(4);
            bar.addView(tab, params);
        }
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, -2);
        barParams.topMargin = dp(8);
        root.addView(bar, barParams);
    }

    private void showEditorTab(int selected) {
        editorTab = selected;
        for (int i = 0; i < editorPages.size(); i++) {
            editorPages.get(i).setVisibility(i == selected ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < editorTabs.size(); i++) {
            Button tab = editorTabs.get(i);
            boolean active = i == selected;
            int color = active ? ACCENT_TEXT : MUTED;
            tab.setSelected(active);
            tab.setTextColor(color);
            tab.setCompoundDrawableTintList(ColorStateList.valueOf(color));
            tab.setBackground(buttonSurface(active ? CONTROL : Color.TRANSPARENT, 18));
            tab.setStateDescription(getString(active ? R.string.option_selected : R.string.option_not_selected));
        }
    }

    private void refreshAppInfo() {
        int matched = 0, manual = 0;
        for (IconRepository.App app : apps) {
            if (pack != null && pack.selected(app, draft.overrides) != null) matched++;
            if (draft.overrides.containsKey(app.key()) && !draft.overrides.get(app.key()).isEmpty()) manual++;
        }
        appInfo.setText(getString(R.string.app_counts, apps.size(), matched, manual));
        if (pack != null) {
            appInfo.append(getString(R.string.export_count,
                    ThemeEngine.exportCount(apps, pack, ThemeEngine.withFilteredSystemIcons(apps, pack,
                            draft.overrides, draft.filters), draft.fullPack)));
        }
    }

    private void refresh() {
        refreshPacks();
        syncStyleControls();
        refreshAppInfo();
        destinationInfo.setText(draft.tree.isEmpty() ? getString(R.string.no_destination) : "Honor/Themes");
        destinationButton.setText(draft.tree.isEmpty() ? R.string.choose_destination : R.string.change_destination);
        shapeButton.setText(getString(R.string.current_shape, getString(draft.shape.label)));
        refreshStylePreview();
        refreshIconSizeInfo();
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
        removeBackground.setVisibility(draft.background.isEmpty() ? View.GONE : View.VISIBLE);
        backgroundSettings.setVisibility(draft.background.isEmpty() ? View.GONE : View.VISIBLE);
        backgroundScope.setVisibility(draft.background.isEmpty() ? View.GONE : View.VISIBLE);
        coverSection.update(!draft.coverPath.isEmpty());
        wallpaperSection.update(!draft.wallpaperPath.isEmpty());
        overlaySection.update(!draft.overlay.isEmpty());
        backgroundSection.update(!draft.background.isEmpty());
    }

    private void updateExportEnabled() {
        if (!editing || exportButton == null) return;
        boolean ready = !busy && pack != null && !draft.title.isBlank() && !draft.tree.isBlank();
        exportButton.setEnabled(ready);
        exportButton.setAlpha(ready ? 1f : .4f);
    }

    private void refreshIconSizeInfo() {
        IconRepository.Pack currentPack = pack;
        if (currentPack == null) {
            iconSizeInfo.setTag(null);
            iconSizeInfo.setText(R.string.no_pack);
            return;
        }
        Map<String, Integer> settings = Map.copyOf(draft.exportSizes);
        String sizeKey = String.join(",", draft.packs) + settings;
        if (sizeKey.equals(iconSizeInfo.getTag())) return;
        iconSizeInfo.setTag(sizeKey);
        iconSizeInfo.setText(R.string.loading);
        thumbnails.execute(() -> {
            String message;
            try {
                ExportSizes sizes = new ExportSizes(currentPack, settings);
                List<String> descriptions = new ArrayList<>();
                for (IconRepository.Pack member : currentPack.members.isEmpty() ? List.of(currentPack) : currentPack.members) {
                    int size = sizes.forIcon(IconRepository.reference(member.packageName, "icon"), currentPack.packageName);
                    descriptions.add(member.label + ": " + size + " × " + size + " px");
                }
                message = String.join("\n", descriptions);
            } catch (IOException | RuntimeException error) {
                Log.e("IconManager", "Не удалось определить разрешение", error);
                message = error.getMessage();
            }
            String shown = message;
            runOnUiThread(() -> { if (!isDestroyed() && sizeKey.equals(iconSizeInfo.getTag())) iconSizeInfo.setText(shown); });
        });
    }

    private void refreshStylePreview() {
        IconRepository.Pack currentPack = pack;
        PreviewEffects effects = previewEffects(true);
        List<IconRepository.App> candidates = List.copyOf(apps);
        if (candidates.isEmpty()) {
            stylePreview.setTag(null);
            return;
        }
        int previewHeight = stylePreview.getWidth() > 0 && stylePreview.getHeight() > 0
                ? Math.max(1, Math.round((float) ShapePreview.WIDTH * stylePreview.getHeight() / stylePreview.getWidth()))
                : ShapePreview.HEIGHT;
        boolean filtersOnly = styleTab == 3;
        Map<String, String> overrides = Map.copyOf(draft.overrides);
        boolean overlayAll = draft.overlayAll, backgroundAll = draft.backgroundAll;
        int iconScale = draft.iconScale;
        String wallpaperPath = draft.wallpaperPath;
        long wallpaperChanged = wallpaperPath.isEmpty() ? 0
                : new File(getFilesDir(), wallpaperPath).lastModified();
        String key = String.join(",", draft.packs) + ":" + effects.key() + ":" + overlayAll + ":" + backgroundAll
                + ":" + iconScale + ":" + new java.util.TreeMap<>(overrides) + ":" + wallpaperPath + ":" + wallpaperChanged
                + ":" + filtersOnly + ":" + previewHeight;
        if (key.equals(stylePreview.getTag())) return;
        ImageView target = stylePreview;
        target.setTag(key);
        if (pendingStylePreview != null) thumbnails.remove(pendingStylePreview);
        Runnable task = () -> {
            int visibleSize = IconShape.SIZE - 2 * IconShape.INSET;
            try {
                if (currentPack != null) visibleSize = currentPack.fallbackSize();
                try (ShapePreview scene = ShapePreview.loadStyle(this, candidates, currentPack, overrides, visibleSize,
                        wallpaperPath, effects.overlay(), effects.background(), effects.shapeFile(), effects.filters(), filtersOnly)) {
                    Bitmap image = scene.renderStyle(effects.shape(), effects.corners(), effects.overlayScale(),
                            effects.overlayClip(), effects.backgroundScale(), effects.backgroundClip(), null,
                            effects.maskScale(), overlayAll, backgroundAll, iconScale, !filtersOnly, previewHeight);
                    runOnUiThread(() -> {
                        if (!isDestroyed() && target == stylePreview && key.equals(target.getTag())) {
                            var previous = target.getDrawable();
                            target.setImageBitmap(image);
                            if (previous instanceof android.graphics.drawable.BitmapDrawable bitmap) bitmap.getBitmap().recycle();
                        } else image.recycle();
                    });
                }
            } catch (IOException | RuntimeException error) {
                Log.e("IconManager", "Style preview failed", error);
                runOnUiThread(() -> {
                    if (!isDestroyed() && target == stylePreview && key.equals(target.getTag())) {
                        showError(error);
                    }
                });
            }
        };
        pendingStylePreview = task;
        thumbnails.execute(task);
    }

    private void openFile(int code, String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime)
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, code);
    }

    private void showShapePicker() {
        Dialog dialog = fullDialog();
        shapeDialog = dialog;
        LinearLayout layout = dialogLayout(dialog, getString(R.string.choose_shape), R.string.shape_preview_help,
                draft.wallpaperPath.isEmpty() ? R.string.shape_preview_gradient : R.string.shape_preview_wallpaper);
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
        String backgroundReference = draft.background;
        List<IconFilters.Filter> filters = IconFilters.copy(draft.filters);
        String[] shapeFileReference = {draft.shapeFile};
        Bitmap[] importedMask = {null};
        int[] overlayScale = {draft.overlayScale};
        int[] maskScale = {draft.maskScale};
        int[] backgroundScale = {draft.backgroundScale};
        boolean[] backgroundClip = {draft.backgroundClip};
        IconShape[] selected = {draft.shape};
        IconShape[] preset = {draft.shape == IconShape.CUSTOM || draft.shape == IconShape.FILE
                ? IconShape.ROUNDED_SQUARE : draft.shape};
        int[] activeTab = {draft.shape == IconShape.CUSTOM ? 1 : draft.shape == IconShape.FILE ? 2 : 0};
        int[] corners = draft.corners.clone();
        boolean[] overlayClip = {draft.overlayClip};
        ShapePreview[] scene = new ShapePreview[1];
        Bitmap[] displayed = new Bitmap[1];
        Button[] applyButton = new Button[1];
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
            Bitmap mask = importedMask[0] == null ? scene[0].shapeMask() : importedMask[0];
            if (selected[0] == IconShape.FILE && mask == null) {
                preview.setImageDrawable(null);
                if (applyButton[0] != null) applyButton[0].setEnabled(false);
                return;
            }
            Bitmap image = scene[0].render(selected[0], corners, overlayScale[0], overlayClip[0],
                    backgroundScale[0], backgroundClip[0], mask, maskScale[0]);
            preview.setImageBitmap(image);
            preview.setContentDescription(getString(R.string.shape_example, getString(selected[0].label),
                    String.join(", ", examples.stream().map(IconRepository.App::label).toList())));
            if (displayed[0] != null) displayed[0].recycle();
            displayed[0] = image;
            if (applyButton[0] != null) applyButton[0].setEnabled(true);
        };
        if (!backgroundReference.isEmpty()) {
            content.addView(createScaleControl(backgroundScale[0], "background-scale", R.string.background_scale,
                    R.string.background_scale_help, value -> { backgroundScale[0] = value; render.run(); }));
            CheckBox clip = new CheckBox(this);
            clip.setTag("background-clip");
            clip.setText(R.string.background_clip);
            clip.setTextColor(INK);
            clip.setChecked(backgroundClip[0]);
            clip.setOnCheckedChangeListener((view, checked) -> { backgroundClip[0] = checked; render.run(); });
            content.addView(withHelp(clip, getString(R.string.background_clip), R.string.background_clip_help));
        }
        if (!overlayReference.isEmpty()) {
            content.addView(createOverlayScaleControl(overlayScale[0], value -> {
                overlayScale[0] = value;
                render.run();
            }));
            CheckBox clip = new CheckBox(this);
            clip.setTag("overlay-clip");
            clip.setText(R.string.overlay_clip);
            clip.setTextColor(INK);
            clip.setChecked(overlayClip[0]);
            clip.setOnCheckedChangeListener((view, checked) -> { overlayClip[0] = checked; render.run(); });
            content.addView(withHelp(clip, getString(R.string.overlay_clip), R.string.overlay_clip_help));
        }
        LinearLayout tabs = row();
        tabs.setBackground(rounded(CONTROL, 24));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, -2);
        tabsParams.topMargin = dp(16);
        content.addView(tabs, tabsParams);
        Button presetsTab = button(R.string.shape_tab_presets, null);
        Button editorTab = button(R.string.shape_tab_editor, null);
        Button fileTab = button(R.string.shape_tab_file, null);
        presetsTab.setTag("shape-tab-presets");
        editorTab.setTag("shape-tab-editor");
        fileTab.setTag("shape-tab-file");
        for (Button tab : new Button[]{presetsTab, editorTab, fileTab}) {
            tab.setStateListAnimator(null);
            tab.setElevation(0);
            tab.setTranslationZ(0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            tabs.addView(tab, params);
        }
        List<IconShape> shapes = List.of(IconShape.ROUNDED_SQUARE, IconShape.SQUIRCLE, IconShape.SQUARE, IconShape.CIRCLE,
                IconShape.HEART, IconShape.STAR, IconShape.HEXAGON, IconShape.PENTAGON, IconShape.TEARDROP, IconShape.NONE);
        LinearLayout options = card(content, R.string.fallback_shape);
        LinearLayout editor = card(content, R.string.shape_editor, R.string.shape_editor_help);
        editor.setTag("shape-editor");
        int[] cornerLabels = {R.string.corner_top_left, R.string.corner_top_right,
                R.string.corner_bottom_right, R.string.corner_bottom_left};
        for (int i = 0; i < 4; i++) {
            int index = i;
            TextView label = text(getString(cornerLabels[i], corners[i]), 14, INK);
            editor.addView(label);
            SeekBar slider = new SeekBar(this);
            slider.setTag("corner-" + i);
            slider.setMax(IconShape.MAX_CORNER);
            slider.setKeyProgressIncrement(1);
            slider.setProgress(corners[i]);
            slider.setContentDescription(label.getText());
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    corners[index] = value;
                    label.setText(getString(cornerLabels[index], value));
                    bar.setContentDescription(label.getText());
                    selected[0] = IconShape.CUSTOM;
                    render.run();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            editor.addView(steppedSlider(slider, value -> {}), new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout fileEditor = card(content, R.string.shape_file_title, R.string.shape_file_help);
        fileEditor.setTag("shape-file");
        TextView fileStatus = text(getString(shapeFileReference[0].isEmpty()
                ? R.string.shape_file_none : R.string.shape_file_selected), 14, INK);
        fileStatus.setTag("shape-file-status");
        fileEditor.addView(fileStatus);
        fileEditor.addView(createScaleControl(maskScale[0], "mask-scale", R.string.mask_scale, R.string.mask_scale_help,
                value -> { maskScale[0] = value; selected[0] = IconShape.FILE; render.run(); }));
        fileEditor.addView(button(R.string.choose_shape_file, view -> {
            pendingShapeMaskImport = selection -> {
                if (shapeDialog != dialog) { selection.bitmap().recycle(); return; }
                if (importedMask[0] != null) importedMask[0].recycle();
                importedMask[0] = selection.bitmap();
                shapeFileReference[0] = selection.reference();
                fileStatus.setText(R.string.shape_file_selected);
                selected[0] = IconShape.FILE;
                render.run();
            };
            openFile(MASK_FILE, "image/png");
        }));
        java.util.function.Consumer<Integer> showTab = tabIndex -> {
            activeTab[0] = tabIndex;
            options.setVisibility(tabIndex == 0 ? View.VISIBLE : View.GONE);
            editor.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
            fileEditor.setVisibility(tabIndex == 2 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < 3; i++) {
                Button tab = new Button[]{presetsTab, editorTab, fileTab}[i];
                boolean active = i == tabIndex;
                tab.setSelected(active);
                tab.setBackground(buttonSurface(active ? ACCENT : CONTROL, 20));
                tab.setTextColor(active ? Color.BLACK : ACCENT_TEXT);
                tab.setStateDescription(getString(active ? R.string.option_selected : R.string.option_not_selected));
            }
        };
        presetsTab.setOnClickListener(view -> {
            showTab.accept(0);
            selected[0] = preset[0];
            render.run();
        });
        editorTab.setOnClickListener(view -> {
            showTab.accept(1);
            selected[0] = IconShape.CUSTOM;
            render.run();
        });
        fileTab.setOnClickListener(view -> {
            showTab.accept(2);
            selected[0] = IconShape.FILE;
            render.run();
        });
        showTab.accept(activeTab[0]);
        LinearLayout line = null;
        for (int i = 0; i < shapes.size(); i++) {
            if (i % 4 == 0) {
                line = row();
                line.setGravity(Gravity.TOP);
                options.addView(line);
            }
            IconShape shape = shapes.get(i);
            LinearLayout choice = shapeChoice(shape, i);
            ImageView silhouette = (ImageView) ((ViewGroup) choice.getChildAt(0)).getChildAt(0);
            silhouettes.add(((android.graphics.drawable.BitmapDrawable) silhouette.getDrawable()).getBitmap());
            View.OnClickListener select = view -> { preset[0] = shape; selected[0] = shape; render.run(); };
            choice.setOnClickListener(select);
            choice.getChildAt(1).setOnClickListener(select);
            line.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
            choices.add(choice);
        }
        while (line.getChildCount() < 4) line.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        Button apply = button(R.string.apply_settings, view -> {
            boolean custom = selected[0] == IconShape.CUSTOM;
            boolean fileMask = selected[0] == IconShape.FILE;
            if (fileMask && (shapeFileReference[0].isEmpty()
                    || importedMask[0] == null && (scene[0] == null || scene[0].shapeMask() == null))) {
                showError(new IOException(getString(R.string.mask_file_missing)));
                return;
            }
            if (draft.shape != selected[0] || draft.overlayScale != overlayScale[0] || draft.overlayClip != overlayClip[0]
                    || draft.backgroundScale != backgroundScale[0] || draft.backgroundClip != backgroundClip[0]
                    || custom && !java.util.Arrays.equals(draft.corners, corners)
                    || !draft.shapeFile.equals(fileMask ? shapeFileReference[0] : "")
                    || draft.maskScale != (fileMask ? maskScale[0] : 100)) {
                draft.shape = selected[0];
                draft.shapeFile = fileMask ? shapeFileReference[0] : "";
                draft.maskScale = fileMask ? maskScale[0] : 100;
                if (custom) draft.corners = corners.clone();
                draft.overlayScale = overlayScale[0];
                draft.overlayClip = overlayClip[0];
                draft.backgroundScale = backgroundScale[0];
                draft.backgroundClip = backgroundClip[0];
                saveDraft();
                refresh();
            }
            dialog.dismiss();
        });
        apply.setMinHeight(dp(56));
        apply.setTextColor(Color.BLACK);
        apply.setBackground(buttonSurface(ACCENT, 28));
        apply.setEnabled(false);
        applyButton[0] = apply;
        layout.addView(apply, new LinearLayout.LayoutParams(-1, -2));
        dialog.setOnDismissListener(ignored -> {
            preview.setImageDrawable(null);
            if (displayed[0] != null) displayed[0].recycle();
            if (scene[0] != null) scene[0].close();
            if (importedMask[0] != null) importedMask[0].recycle();
            for (Bitmap icon : silhouettes) icon.recycle();
            if (pendingShapeMaskImport != null) pendingShapeMaskImport = null;
            if (shapeDialog == dialog) shapeDialog = null;
        });
        render.run();
        showFull(dialog);
        thumbnails.execute(() -> {
            try {
                int visibleSize = currentPack == null ? IconShape.SIZE - 2 * IconShape.INSET : currentPack.fallbackSize();
                ShapePreview loaded = ShapePreview.load(this, examples, visibleSize, wallpaperPath, overlayReference,
                        overlayScale[0], backgroundReference, shapeFileReference[0], filters);
                runOnUiThread(() -> {
                    if (isDestroyed() || !dialog.isShowing()) { loaded.close(); return; }
                    scene[0] = loaded;
                    loading.setVisibility(View.GONE);
                    render.run();
                    apply.setEnabled(selected[0] != IconShape.FILE || loaded.shapeMask() != null || importedMask[0] != null);
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
        if (requestCode == MASK_FILE) {
            java.util.function.Consumer<MaskSelection> selected = pendingShapeMaskImport;
            pendingShapeMaskImport = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            if (selected == null) return;
            Uri maskUri = data.getData();
            work(() -> {
                Bitmap image = IconMask.read(this, maskUri);
                try {
                    String reference = FileIcons.save(this, image);
                    return new MaskSelection(reference, image.copy(Bitmap.Config.ARGB_8888, false));
                } finally { image.recycle(); }
            }, selection -> {
                if (editing || shapeDialog != null && shapeDialog.isShowing()) selected.accept(selection);
                else selection.bitmap().recycle();
            });
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == BACKGROUND_FILE) {
            work(() -> {
                Bitmap image = IconBackground.read(this, uri);
                try { return FileIcons.save(this, image); } finally { image.recycle(); }
            }, reference -> { draft.background = reference; saveDraft(); refresh(); });
        } else if (requestCode == OVERLAY_FILE) {
            work(() -> {
                Bitmap image = IconOverlay.read(this, uri);
                try { return FileIcons.save(this, image); } finally { image.recycle(); }
            }, reference -> { draft.overlay = reference; saveDraft(); refresh(); });
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

    private LinearLayout createOverlayScaleControl(int initial, java.util.function.IntConsumer changed) {
        return createScaleControl(initial, "overlay-scale", R.string.overlay_scale, R.string.overlay_scale_help, changed);
    }

    private LinearLayout createScaleControl(int initial, String tag, int labelRes, int helpRes,
                                           java.util.function.IntConsumer changed) {
        LinearLayout control = column();
        TextView label = text(getString(labelRes, initial), 14, INK);
        control.addView(withHelp(label, getString(labelRes, initial).replaceAll("[:：]?\\s*\\d+\\s*%$", ""), helpRes));
        SeekBar slider = new SeekBar(this);
        slider.setTag(tag);
        slider.setMin(IconOverlay.MIN_SCALE);
        slider.setMax(IconOverlay.MAX_SCALE);
        slider.setKeyProgressIncrement(1);
        slider.setProgress(initial);
        slider.setContentDescription(getString(labelRes, initial));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                label.setText(getString(labelRes, value));
                bar.setContentDescription(label.getText());
                if (fromUser) changed.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        control.addView(steppedSlider(slider, changed), new LinearLayout.LayoutParams(-1, -2));
        return control;
    }

    private void previewBackground(Bitmap background) {
        Dialog dialog = fullDialog();
        LinearLayout content = scrollingDialogLayout(dialog, getString(R.string.background_title), R.string.background_help);
        ImageView[] examples = new ImageView[6];
        TextView[] captions = new TextView[6];
        for (int type = 0; type < 2; type++) {
            content.addView(text(getString(type == 0 ? R.string.background_example_system
                    : R.string.background_example_pack), 14, INK));
            LinearLayout row = row();
            row.setGravity(Gravity.TOP);
            for (int i = 0; i < 3; i++) {
                int index = type * 3 + i;
                String tag = "background-example-" + (type == 0 ? "system-" : "pack-") + i;
                examples[index] = backgroundExample(row, tag);
                captions[index] = row.findViewWithTag(tag + "-label");
            }
            content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        int[] scale = {draft.backgroundScale};
        int[] iconScale = {draft.iconScale};
        boolean[] clip = {draft.backgroundClip}, all = {draft.backgroundAll}, closed = {false};
        // First row: system sources. Second row: pack artwork. The lock protects bitmap lifetime.
        Bitmap[] sources = new Bitmap[6];
        boolean[] loaded = {false};
        String[] labels = new String[6];
        Bitmap[] shown = new Bitmap[6];
        java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
        Runnable[] pending = {null};
        IconRepository.Pack currentPack = pack;
        List<IconRepository.App> candidates = List.copyOf(apps);
        Map<String, String> overrides = Map.copyOf(draft.overrides);
        Runnable render = () -> {
            if (pending[0] != null) thumbnails.remove(pending[0]);
            int current = generation.incrementAndGet();
            PreviewEffects systemEffects = importedBackgroundEffects(true, scale[0], clip[0], all[0], iconScale[0]);
            PreviewEffects packEffects = importedBackgroundEffects(false, scale[0], clip[0], all[0], iconScale[0]);
            Runnable task = () -> {
                Bitmap[] rendered = new Bitmap[6];
                try {
                    synchronized (sources) {
                        if (closed[0] || current != generation.get()) return;
                        if (!loaded[0]) {
                            for (int i = 0; i < sources.length; i++) {
                                if (sources[i] != null) sources[i].recycle();
                                sources[i] = null;
                            }
                            loadBackgroundExamples(currentPack, candidates, overrides, systemEffects, sources, labels);
                            loaded[0] = true;
                        }
                        int visibleSize = currentPack == null ? IconShape.SIZE - 2 * IconShape.INSET
                                : currentPack.fallbackSize();
                        Bitmap mask = IconMask.load(this, systemEffects.shapeFile(), systemEffects.maskScale());
                        try {
                            for (int i = 0; i < 3; i++) {
                                if (sources[i] == null) continue;
                                rendered[i] = systemEffects.shape().preview(sources[i], visibleSize,
                                        systemEffects.corners(), mask, systemEffects.filters());
                                rendered[i] = withPreviewEffects(rendered[i], systemEffects, visibleSize, true, mask, background);
                            }
                        } finally { if (mask != null) mask.recycle(); }
                        for (int i = 3; i < 6; i++) {
                            if (sources[i] != null) rendered[i] = withPreviewEffects(
                                    sources[i].copy(Bitmap.Config.ARGB_8888, true), packEffects, currentPack, background);
                        }
                    }
                    runOnUiThread(() -> {
                        if (isDestroyed() || !dialog.isShowing() || current != generation.get()) {
                            for (Bitmap image : rendered) if (image != null) image.recycle();
                            return;
                        }
                        for (int i = 0; i < examples.length; i++) {
                            examples[i].setImageBitmap(rendered[i]);
                            captions[i].setText(rendered[i] == null ? getString(R.string.background_example_missing) : labels[i]);
                            examples[i].setContentDescription(getString(i < 3 ? R.string.background_example_system
                                    : R.string.background_example_pack) + ": " + captions[i].getText());
                            if (shown[i] != null) shown[i].recycle();
                            shown[i] = rendered[i];
                        }
                    });
                } catch (android.content.pm.PackageManager.NameNotFoundException | IOException | RuntimeException error) {
                    for (Bitmap image : rendered) if (image != null) image.recycle();
                    Log.e("IconManager", "Background preview failed", error);
                    runOnUiThread(() -> {
                        if (!isDestroyed() && dialog.isShowing() && current == generation.get()) showError(error);
                    });
                }
            };
            pending[0] = task;
            thumbnails.execute(task);
        };
        LinearLayout scaleControl = createScaleControl(scale[0], "background-scale", R.string.background_scale,
                R.string.background_scale_help, value -> { scale[0] = value; render.run(); });
        LinearLayout.LayoutParams scaleParams = new LinearLayout.LayoutParams(-1, -2);
        scaleParams.topMargin = dp(16);
        content.addView(scaleControl, scaleParams);
        content.addView(createScaleControl(iconScale[0], "icon-scale", R.string.icon_scale,
                R.string.icon_scale_help, value -> { iconScale[0] = value; render.run(); }));
        CheckBox clipBox = new CheckBox(this);
        clipBox.setTag("background-clip");
        clipBox.setText(R.string.background_clip);
        clipBox.setTextColor(INK);
        clipBox.setChecked(clip[0]);
        clipBox.setOnCheckedChangeListener((view, checked) -> { clip[0] = checked; render.run(); });
        content.addView(withHelp(clipBox, getString(R.string.background_clip), R.string.background_clip_help));
        RadioGroup scope = new RadioGroup(this);
        scope.setTag("background-import-scope");
        for (int label : new int[]{R.string.overlay_fallback, R.string.overlay_all}) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId()); option.setText(label); option.setTextColor(INK);
            scope.addView(option);
        }
        scope.check(scope.getChildAt(all[0] ? 1 : 0).getId());
        scope.setOnCheckedChangeListener((group, id) -> { all[0] = id == scope.getChildAt(1).getId(); render.run(); });
        content.addView(scope);
        content.addView(button(R.string.use_background, view -> {
            Bitmap copy = background.copy(Bitmap.Config.ARGB_8888, false);
            int chosenScale = scale[0];
            int chosenIconScale = iconScale[0];
            boolean chosenClip = clip[0], chosenAll = all[0];
            dialog.dismiss();
            work(() -> {
                try { return FileIcons.save(this, copy); }
                finally { copy.recycle(); }
            }, reference -> {
                draft.background = reference;
                draft.backgroundScale = chosenScale;
                draft.iconScale = chosenIconScale;
                draft.backgroundClip = chosenClip;
                draft.backgroundAll = chosenAll;
                saveDraft();
                if (backgroundScope != null) backgroundScope.check(backgroundScope.getChildAt(chosenAll ? 1 : 0).getId());
                refresh();
            });
        }));
        backgroundDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            generation.incrementAndGet();
            if (pending[0] != null) thumbnails.remove(pending[0]);
            for (ImageView image : examples) image.setImageDrawable(null);
            for (Bitmap image : shown) if (image != null) image.recycle();
            synchronized (sources) {
                closed[0] = true;
                for (Bitmap source : sources) if (source != null) source.recycle();
                background.recycle();
            }
            if (backgroundDialog == dialog) backgroundDialog = null;
        });
        render.run();
        showFull(dialog);
    }

    private void addFilterControls(LinearLayout content, List<IconFilters.Filter> working, Runnable render) {
        LinearLayout list = column();
        list.setTag("filter-list");
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, -2);
        listParams.topMargin = dp(16);
        content.addView(list, listParams);
        TextView empty = text(getString(R.string.filters_empty), 14, MUTED);
        content.addView(empty);
        Runnable[] rebuild = {null};
        rebuild[0] = () -> {
            list.removeAllViews();
            empty.setVisibility(working.isEmpty() ? View.VISIBLE : View.GONE);
            for (int index = 0; index < working.size(); index++) {
                IconFilters.Filter filter = working.get(index);
                int position = index;
                String tag = "filter-" + index;
                LinearLayout card = column();
                card.setTag(tag);
                card.setBackground(rounded(CONTROL, 20));
                card.setPadding(dp(12), dp(4), dp(4), dp(12));
                LinearLayout header = row();
                header.addView(text(getString(R.string.filter_title, index + 1, getString(filter.label())), 16, INK),
                        new LinearLayout.LayoutParams(0, -2, 1));
                Runnable changed = () -> { rebuild[0].run(); render.run(); };
                android.widget.ImageButton up = iconButton(R.drawable.ic_collapse, R.string.filter_move_up, view -> {
                    java.util.Collections.swap(working, position, position - 1); changed.run();
                });
                android.widget.ImageButton down = iconButton(R.drawable.ic_expand, R.string.filter_move_down, view -> {
                    java.util.Collections.swap(working, position, position + 1); changed.run();
                });
                android.widget.ImageButton remove = iconButton(R.drawable.ic_delete, R.string.filter_remove, view -> {
                    working.remove(position); changed.run();
                });
                up.setEnabled(position > 0);
                down.setEnabled(position < working.size() - 1);
                for (android.widget.ImageButton action : new android.widget.ImageButton[]{up, down, remove}) {
                    action.setBackground(buttonSurface(Color.TRANSPARENT, 24));
                    action.setImageTintList(ColorStateList.valueOf(MUTED));
                    action.setAlpha(action.isEnabled() ? 1f : .3f);
                    header.addView(action, new LinearLayout.LayoutParams(dp(48), dp(48)));
                }
                up.setTag(tag + "-up"); down.setTag(tag + "-down"); remove.setTag(tag + "-remove");
                card.addView(header);
                if (filter instanceof IconFilters.AdjustableFilter adjustable) {
                    for (int field = 0; field < adjustable.fields.length; field++) {
                        int value = field;
                        card.addView(filterSlider(getString(adjustable.labels[field]), adjustable.min[field],
                                adjustable.max[field], adjustable.values[field], tag + "-" + adjustable.fields[field],
                                adjustable.valueFormat(field), number -> { adjustable.set(value, number); render.run(); }));
                    }
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.bottomMargin = dp(12);
                list.addView(card, params);
            }
        };
        Button add = button(R.string.filter_add, view -> {
            if (working.size() >= IconFilters.MAX_FILTERS) {
                showError(new IOException(getString(R.string.filters_limit, IconFilters.MAX_FILTERS)));
                return;
            }
            String[] names = new String[IconFilters.TYPES.length];
            for (int i = 0; i < names.length; i++) names[i] = getString(IconFilters.label(IconFilters.TYPES[i]));
            filterTypeDialog = new AlertDialog.Builder(this).setTitle(R.string.filter_add).setItems(names, (menu, which) -> {
                working.add(IconFilters.create(IconFilters.TYPES[which]));
                rebuild[0].run();
                render.run();
            }).show();
        });
        add.setTag("filter-add");
        content.addView(add);
        rebuild[0].run();
    }

    private LinearLayout filterSlider(String name, int min, int max, int initial, String tag, int format,
                                      java.util.function.IntConsumer changed) {
        LinearLayout control = column();
        TextView label = text(getString(format, name, initial), 14, INK);
        control.addView(label);
        SeekBar slider = new SeekBar(this);
        slider.setTag(tag);
        slider.setMin(min);
        slider.setMax(max);
        slider.setKeyProgressIncrement(1);
        slider.setProgress(initial);
        slider.setContentDescription(label.getText());
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                label.setText(getString(format, name, value));
                bar.setContentDescription(label.getText());
                if (fromUser) changed.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        control.addView(steppedSlider(slider, changed), new LinearLayout.LayoutParams(-1, -2));
        return control;
    }

    private ImageView backgroundExample(LinearLayout parent, String tag) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView image = new ImageView(this);
        image.setTag(tag);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(12), dp(12), dp(12), dp(12));
        image.setBackground(rounded(CONTROL, 16));
        cell.addView(image, new LinearLayout.LayoutParams(-1, dp(96)));
        TextView caption = text(getString(R.string.loading), 13, MUTED);
        caption.setTag(tag + "-label");
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setMaxLines(2);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(dp(4)); params.setMarginEnd(dp(4));
        parent.addView(cell, params);
        return image;
    }

    /** Picks up to three distinct applications per type without repeating launcher aliases. */
    private void loadBackgroundExamples(IconRepository.Pack currentPack, List<IconRepository.App> candidates,
                                        Map<String, String> overrides, PreviewEffects effects, Bitmap[] sources,
                                        String[] labels) throws android.content.pm.PackageManager.NameNotFoundException, IOException {
        java.util.Set<String> selected = new java.util.HashSet<>();
        int systemCount = 0, packCount = 0;
        for (IconRepository.App app : candidates) {
            if (!selected.add(app.component().getPackageName())) continue;
            String name = currentPack == null ? null : currentPack.selected(app, overrides);
            if (name != null) {
                if (packCount < 3) {
                    int index = 3 + packCount++;
                    sources[index] = currentPack.render(name, 128);
                    labels[index] = app.label();
                }
            } else if (systemCount < 3) {
                var icon = IconRepository.unthemedIcon(getPackageManager(), app.component());
                sources[systemCount] = IconShape.previewSource(icon, effects.shape() != IconShape.NONE);
                labels[systemCount++] = app.label();
            }
            if (systemCount == 3 && packCount == 3) break;
        }
    }

    private PreviewEffects importedBackgroundEffects(boolean fallback, int scale, boolean clip, boolean all, int iconScale) {
        PreviewEffects base = previewEffects(fallback);
        // A non-empty marker enables the imported bitmap; the PNG has no saved reference yet.
        return new PreviewEffects(base.overlay(), base.overlayScale(), base.overlayClip(),
                fallback || all ? "imported" : "", scale, clip, base.shape(), base.corners(), base.shapeFile(),
                base.maskScale(), fallback ? 100 : iconScale, base.filters());
    }

    private void previewOverlay(Bitmap overlay) {
        Bitmap preview;
        try { preview = previewLayer(overlay, false); }
        catch (IOException | RuntimeException error) { overlay.recycle(); showError(error); return; }
        Dialog dialog = fullDialog();
        LinearLayout content = scrollingDialogLayout(dialog, getString(R.string.overlay_title), R.string.overlay_help);
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
            image.setImageDrawable(null);
            preview.recycle(); overlay.recycle();
            if (overlayDialog == dialog) overlayDialog = null;
        });
        showFull(dialog);
    }

    private Bitmap previewLayer(Bitmap imported, boolean importingBackground) throws IOException {
        Bitmap preview = null, overlay = null, background = null, shapeMask = null;
        try {
            int visibleSize = pack == null ? IconShape.SIZE - 2 * IconShape.INSET : pack.fallbackSize();
            shapeMask = IconMask.load(this, draft.shape == IconShape.FILE ? draft.shapeFile : "", draft.maskScale);
            Bitmap source = IconShape.previewSource(getDrawable(R.drawable.app_icon), draft.shape != IconShape.NONE);
            try { preview = draft.shape.preview(source, visibleSize, draft.corners, shapeMask, draft.filters); }
            finally { source.recycle(); }
            overlay = importingBackground ? IconOverlay.load(this, draft.overlay, draft.overlayScale)
                    : IconOverlay.scaled(imported, draft.overlayScale);
            background = importingBackground
                    ? IconOverlay.scaled(imported, draft.backgroundScale, R.string.background_scale_invalid)
                    : IconBackground.load(this, draft.background, draft.backgroundScale);
            if (overlay != null) {
                Bitmap border = IconOverlay.border(overlay, draft.shape, draft.corners, visibleSize, draft.overlayClip, shapeMask);
                try { IconOverlay.draw(preview, border); }
                finally { border.recycle(); }
            }
            if (background != null) {
                Bitmap backdrop = IconBackground.render(background, draft.shape, draft.corners, visibleSize,
                        draft.backgroundClip, shapeMask);
                try { IconBackground.draw(preview, backdrop); }
                finally { backdrop.recycle(); }
            }
            return preview;
        } catch (IOException | RuntimeException error) {
            if (preview != null) preview.recycle();
            throw error;
        } finally {
            if (overlay != null) overlay.recycle();
            if (background != null) background.recycle();
            if (shapeMask != null) shapeMask.recycle();
        }
    }

    private void cropCover(Bitmap source) {
        Dialog dialog = fullDialog();
        cropDialog = dialog;
        LinearLayout layout = dialogLayout(dialog, getString(R.string.crop_cover), R.string.crop_help);
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
        zoom.setTag("crop-zoom");
        layout.addView(steppedSlider(zoom, value -> {}), new LinearLayout.LayoutParams(-1, -2));
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
        draft.exportSizes.remove(packs.remove(index).packageName);
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
        packInfo.setVisibility(packs.isEmpty() ? View.VISIBLE : View.GONE);
        packInfo.setText(R.string.no_pack);
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
        LinearLayout details = column();
        details.addView(label);
        Integer chosen = draft.exportSizes.get(member.packageName);
        Button resolution = button(R.string.export_resolution, view -> chooseExportSize(member));
        resolution.setTag("export-size:" + member.packageName);
        resolution.setText(getString(R.string.export_resolution) + ": "
                + (chosen == null ? getString(R.string.export_size_native) : chosen + " px"));
        resolution.setSingleLine(true);
        resolution.setAutoSizeTextTypeUniformWithConfiguration(8, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        resolution.setContentDescription(member.label + ", " + resolution.getText());
        details.addView(resolution);
        item.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageButton remove = iconButton(R.drawable.ic_delete, R.string.remove_pack,
                view -> removePack(packList.indexOfChild(item)));
        remove.setContentDescription(getString(R.string.remove_pack_named, member.label));
        remove.setTooltipText(getString(R.string.remove_pack_named, member.label));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        removeParams.setMargins(dp(8), 0, dp(8), 0);
        item.addView(remove, removeParams);
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

    private void chooseExportSize(IconRepository.Pack member) {
        String[] labels = new String[ExportSizes.OPTIONS.size() + 1];
        labels[0] = getString(R.string.export_size_native);
        for (int i = 0; i < ExportSizes.OPTIONS.size(); i++) labels[i + 1] = ExportSizes.OPTIONS.get(i) + " px";
        Integer chosen = draft.exportSizes.get(member.packageName);
        int selected = chosen == null ? 0 : ExportSizes.OPTIONS.indexOf(chosen) + 1;
        exportSizeDialog = new AlertDialog.Builder(this).setTitle(member.label)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    if (which == 0) draft.exportSizes.remove(member.packageName);
                    else draft.exportSizes.put(member.packageName, ExportSizes.OPTIONS.get(which - 1));
                    saveDraft(); refreshPacks(); refreshIconSizeInfo();
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
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
        content.addView(withHelp(text(getString(R.string.file_icon_preview), 22, INK),
                getString(R.string.file_icon_preview), R.string.file_icon_help));
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setPadding(dp(16), dp(16), dp(16), dp(16));
        preview.setBackground(rounded(CONTROL, 16));
        preview.setImageBitmap(image);
        preview.setContentDescription(getString(R.string.file_icon_preview));
        PreviewEffects effects = previewEffects(false);
        IconRepository.Pack basePack = pack;
        if (!effects.overlay().isEmpty() || !effects.background().isEmpty() || effects.iconScale() != 100) {
            String key = "file-preview:" + appKey + ":" + effects.key() + ":" + String.join(",", draft.packs);
            preview.setTag(key);
            Bitmap rendering = image.copy(Bitmap.Config.ARGB_8888, true);
            thumbnails.execute(() -> {
                try {
                    Bitmap bitmap = withPreviewEffects(rendering, effects, basePack);
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
        PreviewEffects effects = previewEffects(true);
        IconRepository.Pack currentPack = pack;
        String key = "system:" + app.key() + ":" + effects.key() + ":" + String.join(",", draft.packs);
        view.setTag(key);
        view.setImageResource(R.drawable.app_icon);
        Bitmap cached = images.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        thumbnails.execute(() -> {
            Bitmap mask = null;
            try {
                var icon = IconRepository.unthemedIcon(getPackageManager(), app.component());
                int visibleSize = currentPack == null ? IconShape.SIZE - 2 * IconShape.INSET : currentPack.fallbackSize();
                mask = IconMask.load(this, effects.shapeFile(), effects.maskScale());
                Bitmap bitmap = systemPreview(icon, effects, visibleSize, mask);
                withPreviewEffects(bitmap, effects, visibleSize, true, mask);
                images.put(key, bitmap);
                runOnUiThread(() -> { if (!isDestroyed() && key.equals(view.getTag())) view.setImageBitmap(bitmap); });
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // Apps that are not installed have no system icon; the placeholder stays.
            } catch (IOException | RuntimeException error) {
                reportImageError(view, key, app.key(), error);
            } finally { if (mask != null) mask.recycle(); }
        });
    }

    private void bindImage(ImageView view, String name) {
        bindImage(view, pack, name);
    }

    private void bindImage(ImageView view, IconRepository.Pack current, String name) {
        PreviewEffects effects = previewEffects(false);
        IconRepository.Pack basePack = pack;
        String key = (name == null ? "" : IconRepository.reference(
                IconRepository.sourcePackage(name, current.packageName), IconRepository.resourceName(name)))
                + ":" + effects.key() + ":" + String.join(",", draft.packs);
        view.setTag(key);
        if (name == null) { view.setImageResource(R.drawable.app_icon); return; }
        Bitmap cached = images.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        view.setImageResource(R.drawable.app_icon);
        Runnable task = () -> {
            try {
                Bitmap bitmap = withPreviewEffects(current.render(name, 128), effects, basePack);
                images.put(key, bitmap);
                runOnUiThread(() -> { if (!isDestroyed() && key.equals(view.getTag())) view.setImageBitmap(bitmap); });
            } catch (IOException | RuntimeException error) {
                reportImageError(view, key, name, error);
            }
        };
        thumbnails.execute(task);
    }

    private record PreviewEffects(String overlay, int overlayScale, boolean overlayClip,
                                  String background, int backgroundScale, boolean backgroundClip,
                                  IconShape shape, int[] corners, String shapeFile, int maskScale, int iconScale,
                                  List<IconFilters.Filter> filters) {
        String key() {
            return overlay + ":" + overlayScale + ":" + overlayClip + ":" + background + ":" + backgroundScale
                    + ":" + backgroundClip + ":" + shapeKey(shape, corners, shapeFile) + ":" + maskScale + ":" + iconScale
                    + ":" + IconFilters.key(filters);
        }
    }

    /** Builds a system icon exactly as the export does: filters first, then the fallback shape. */
    private static Bitmap systemPreview(android.graphics.drawable.Drawable icon, PreviewEffects effects,
                                        int visibleSize, Bitmap mask) {
        Bitmap source = IconShape.previewSource(icon, effects.shape() != IconShape.NONE);
        try {
            return effects.shape().preview(source, visibleSize, effects.corners(), mask, effects.filters());
        } finally { source.recycle(); }
    }

    private PreviewEffects previewEffects(boolean fallback) {
        return new PreviewEffects(fallback || draft.overlayAll ? draft.overlay : "", draft.overlayScale, draft.overlayClip,
                fallback || draft.backgroundAll ? draft.background : "", draft.backgroundScale, draft.backgroundClip,
                draft.shape, draft.corners.clone(), draft.shape == IconShape.FILE ? draft.shapeFile : "", draft.maskScale,
                fallback ? 100 : draft.iconScale, fallback ? IconFilters.copy(draft.filters) : List.of());
    }

    private Bitmap withPreviewEffects(Bitmap image, PreviewEffects effects, int visibleSize, boolean fallback) throws IOException {
        return withLoadedMask(image, effects, visibleSize, fallback, null);
    }

    private Bitmap withLoadedMask(Bitmap image, PreviewEffects effects, int visibleSize, boolean fallback,
                                  Bitmap importedBackground) throws IOException {
        Bitmap shapeMask;
        try { shapeMask = IconMask.load(this, effects.shapeFile(), effects.maskScale()); }
        catch (IOException | RuntimeException error) { image.recycle(); throw error; }
        try { return withPreviewEffects(image, effects, visibleSize, fallback, shapeMask, importedBackground); }
        finally { if (shapeMask != null) shapeMask.recycle(); }
    }

    private Bitmap withPreviewEffects(Bitmap image, PreviewEffects effects, int visibleSize, boolean fallback,
                                      Bitmap shapeMask) throws IOException {
        return withPreviewEffects(image, effects, visibleSize, fallback, shapeMask, null);
    }

    /** importedBackground replaces the stored background reference while a new PNG awaits confirmation. */
    private Bitmap withPreviewEffects(Bitmap image, PreviewEffects effects, int visibleSize, boolean fallback,
                                      Bitmap shapeMask, Bitmap importedBackground) throws IOException {
        Bitmap overlay = null, background = null;
        try {
            overlay = IconOverlay.load(this, effects.overlay(), effects.overlayScale());
            background = effects.background().isEmpty() ? null : importedBackground != null
                    ? IconBackground.prepare(importedBackground, effects.backgroundScale())
                    : IconBackground.load(this, effects.background(), effects.backgroundScale());
            if (overlay != null && fallback) {
                Bitmap border = IconOverlay.border(overlay, effects.shape(), effects.corners(),
                        visibleSize, effects.overlayClip(), shapeMask);
                try { IconOverlay.draw(image, border); }
                finally { border.recycle(); }
            } else IconOverlay.draw(image, overlay, effects.overlayClip());
            if (background != null) {
                Bitmap backdrop = IconBackground.render(background, effects.shape(), effects.corners(),
                        visibleSize, effects.backgroundClip(), shapeMask);
                try { IconBackground.draw(image, backdrop); }
                finally { backdrop.recycle(); }
            }
            return image;
        } catch (IOException | RuntimeException error) {
            image.recycle();
            throw error;
        } finally {
            if (overlay != null) overlay.recycle();
            if (background != null) background.recycle();
        }
    }

    private Bitmap withPreviewEffects(Bitmap image, PreviewEffects effects, IconRepository.Pack basePack) throws IOException {
        return withPreviewEffects(image, effects, basePack, null);
    }

    private Bitmap withPreviewEffects(Bitmap image, PreviewEffects effects, IconRepository.Pack basePack,
                                      Bitmap importedBackground) throws IOException {
        image = IconRepository.Pack.scaleArtwork(image, effects.iconScale());
        int visibleSize = IconShape.SIZE - 2 * IconShape.INSET;
        try {
            if (basePack != null && !effects.background().isEmpty()
                    && effects.backgroundClip() && effects.shape() != IconShape.NONE) {
                visibleSize = basePack.fallbackSize();
            }
        } catch (IOException | RuntimeException error) { image.recycle(); throw error; }
        return withLoadedMask(image, effects, visibleSize, false, importedBackground);
    }

    private static String shapeKey(IconShape shape, int[] corners, String shapeFile) {
        return shape == IconShape.CUSTOM ? shape + java.util.Arrays.toString(corners)
                : shape == IconShape.FILE ? shape + ":" + shapeFile : shape.name();
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
            Bitmap overlay = null, background = null, backdrop = null, shapeMask = null;
            try {
                int iconSize = new ExportSizes(currentPack, snapshot.exportSizes).maximum;
                overlay = IconOverlay.load(this, snapshot.overlay, snapshot.overlayScale, iconSize);
                background = IconBackground.load(this, snapshot.background, snapshot.backgroundScale, iconSize);
                shapeMask = IconMask.load(this, snapshot.shapeFile, snapshot.maskScale, iconSize);
                if (background != null && snapshot.backgroundAll) {
                    backdrop = IconBackground.render(background, shape, snapshot.corners,
                            currentPack.fallbackSize(), snapshot.backgroundClip, shapeMask);
                }
                if (coverPath.isEmpty()) cover = ThemeEngine.cover(currentPack, apps, overrides,
                        snapshot.overlayAll ? overlay : null, snapshot.overlayClip, backdrop, snapshot.iconScale);
                else {
                    cover = BitmapFactory.decodeFile(new File(getFilesDir(), coverPath).getPath());
                    if (cover == null) throw new IOException(App.text(R.string.cover_read_error));
                }
                ThemeEngine.Result result = ThemeEngine.export(this, outputTitle, outputName, fullPack, shape,
                        snapshot.corners, clearFolderBackground, currentPack, apps, overrides,
                        cover, tree, null, snapshot.wallpaperPath.isEmpty() ? null
                                : new File(getFilesDir(), snapshot.wallpaperPath), overlay, snapshot.overlayAll,
                        snapshot.overlayClip, background, snapshot.backgroundAll, snapshot.backgroundClip, shapeMask,
                        snapshot.iconScale, snapshot.filters, snapshot.exportSizes,
                        (done, total) -> {
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
            } finally {
                if (cover != null) cover.recycle();
                if (overlay != null) overlay.recycle();
                if (background != null) background.recycle();
                if (backdrop != null) backdrop.recycle();
                if (shapeMask != null) shapeMask.recycle();
            }
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
                    + "\n\n" + getString(R.string.template_saved, saved.template().name())
                    + exportSizeMessage(saved.theme()));
        });
    }

    AlertDialog showExportResult(ThemeEngine.Result result) {
        exportDialog = new AlertDialog.Builder(this).setTitle(R.string.export_success)
                .setMessage(getString(result.clearFolderBackground() ? R.string.export_done_full_theme
                        : R.string.export_done, "Honor/Themes/" + result.filename()) + exportSizeMessage(result))
                .setPositiveButton(R.string.open_themes, (dialog, which) -> openThemes()).show();
        return exportDialog;
    }

    private String exportSizeMessage(ThemeEngine.Result result) {
        if (result.bytes() == 0) return "";
        String size = android.text.format.Formatter.formatFileSize(this, result.bytes());
        return "\n\n" + getString(R.string.export_file_size, size)
                + (ExportSizes.oversized(result.bytes()) ? "\n\n" + getString(R.string.export_size_warning) : "");
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
    private LinearLayout scrollingDialogLayout(Dialog dialog, String heading, int... help) {
        LinearLayout layout = dialogLayout(dialog, heading, help);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        scroll.addView(content);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return content;
    }

    private LinearLayout dialogLayout(Dialog dialog, String heading, int... help) {
        LinearLayout layout = column(); layout.setPadding(dp(16), dp(12), dp(16), dp(12)); layout.setBackgroundColor(BG);
        LinearLayout bar = row();
        TextView label = text(heading, 24, INK);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setMinHeight(dp(56));
        label.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (help.length > 0) {
            LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            helpParams.setMarginStart(dp(8));
            bar.addView(helpButton(heading, help), helpParams);
        }
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
    private LinearLayout card(LinearLayout parent, int heading, int... help) {
        LinearLayout panel = column(); panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(rounded(PANEL, 24));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(16); params.bottomMargin = dp(2);
        parent.addView(panel, params);
        if (heading != 0) {
            TextView label = text(getString(heading), 18, INK);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setAccessibilityHeading(true);
            panel.addView(help.length == 0 ? label : withHelp(label, getString(heading), help));
        }
        return panel;
    }

    private final class OptionalSection {
        final LinearLayout content = column();
        final Button toggle;
        private boolean populated;
        private final boolean collapsible;

        OptionalSection(LinearLayout parent, int heading, boolean populated, int... help) {
            this(parent, heading, populated, true, help);
        }

        OptionalSection(LinearLayout parent, int heading, boolean populated, boolean collapsible, int... help) {
            this.populated = populated;
            this.collapsible = collapsible;
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
            if (collapsible) panel.addView(help.length == 0 ? toggle : withHelp(toggle, getString(heading), help),
                    new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, -2);
            contentParams.topMargin = collapsible ? dp(8) : 0;
            panel.addView(content, contentParams);
            setExpanded(populated);
        }

        void setExpanded(boolean expanded) {
            expanded = !collapsible || expanded;
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
        state.putInt("editorTab", editorTab);
        state.putInt("styleTab", styleTab);
    }
    @Override protected void onStop() { saveDraft(); super.onStop(); }
    @Override protected void onDestroy() {
        if (packDialog != null) packDialog.dismiss();
        if (cropDialog != null) cropDialog.dismiss();
        if (iconDialog != null) iconDialog.dismiss();
        if (exportDialog != null) exportDialog.dismiss();
        if (exportSizeDialog != null) exportSizeDialog.dismiss();
        if (errorDialog != null) errorDialog.dismiss();
        if (deleteDialog != null) deleteDialog.dismiss();
        if (progressDialog != null) progressDialog.dismiss();
        if (fileIconDialog != null) fileIconDialog.dismiss();
        if (helpDialog != null) helpDialog.dismiss();
        if (settingsDialog != null) settingsDialog.dismiss();
        if (overlayDialog != null) overlayDialog.dismiss();
        if (backgroundDialog != null) backgroundDialog.dismiss();
        worker.shutdown(); thumbnails.shutdown(); super.onDestroy();
    }
}
