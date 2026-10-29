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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
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
    private static final int COVER = 11, DIRECTORY = 12, WALLPAPER = 13, ICON_FILE = 14;
    private static final int BG = Color.BLACK, PANEL = Color.rgb(24, 24, 24);
    private static final int ACCENT = Color.rgb(255, 193, 7), MUTED = Color.rgb(189, 189, 189);
    private static final int CONTROL = Color.rgb(36, 36, 36);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> images = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private Draft draft;
    private IconRepository repository;
    private IconRepository.Pack pack;
    private List<IconRepository.App> apps = List.of();
    private LinearLayout root;
    private EditText title, filename;
    private Button exportButton;
    private TextView packInfo, appInfo, destinationInfo, status, templateHelp;
    private ImageView coverPreview, packPreview, wallpaperPreview;
    private ProgressBar operationProgress;
    private Dialog packDialog;
    private Dialog cropDialog;
    private Dialog iconDialog;
    private AlertDialog exportDialog, errorDialog, deleteDialog;
    private Dialog progressDialog;
    private TextView progressMessage;
    private String pendingIconApp;
    private Runnable pendingIconChanged;
    private Dialog fileIconDialog;
    private RadioGroup exportScope;
    private ImageView shapePreview;
    private boolean busy;
    private boolean editing;
    private TemplateStore templates;
    private CheckBox saveAsNew;
    private record SavedExport(ThemeEngine.Result theme, TemplateStore.Entry template) {}
    private final java.util.Set<String> reportedImageErrors = new java.util.HashSet<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) pendingIconApp = savedInstanceState.getString("pendingIconApp");
        repository = new IconRepository(this);
        templates = new TemplateStore(this);
        Exception draftError = null;
        try { draft = Draft.load(this); }
        catch (org.json.JSONException e) { draft = new Draft(); draftError = e; }
        showTemplates();
        if (draftError != null) showError(draftError);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (busy) showError(new IOException(getString(R.string.busy)));
            else if (editing) { saveDraft(); showTemplates(); }
            else finish();
        });
    }

    void openEditor() {
        editing = true;
        pack = null;
        images.evictAll();
        buildScreen();
        work(getString(R.string.loading_template), () -> {
            apps = repository.apps();
            return draft.pack.isEmpty() ? null : repository.load(draft.pack);
        }, loaded -> { pack = loaded; refresh(); });
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
        TextView heading = text(getString(R.string.templates), 32, Color.WHITE);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMinHeight(dp(72));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        root.addView(text(getString(R.string.templates_help), 14, MUTED));
        Button create = button(R.string.new_theme, view -> {
            String tree = draft.tree;
            draft = new Draft();
            draft.tree = tree;
            saveDraft();
            openEditor();
        });
        create.setTextColor(BG);
        create.setBackground(buttonSurface(ACCENT, 28));
        root.addView(create);
        if (!draft.pack.isEmpty() || !draft.title.isEmpty() || !draft.overrides.isEmpty()) {
            root.addView(button(R.string.resume_draft, view -> openEditor()));
        }
        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(BG));
        list.setDividerHeight(dp(12));
        list.setPadding(0, dp(16), 0, dp(12));
        list.setClipToPadding(false);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        addOperationStatus();
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
            if (entries.isEmpty()) status.setText(R.string.no_templates);
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
                        TextView label = text("", 16, Color.WHITE);
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

    private void addOperationStatus() {
        status = text(getString(R.string.ready), 13, MUTED);
        LinearLayout progressRow = row();
        operationProgress = new ProgressBar(this);
        operationProgress.setIndeterminate(true);
        operationProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        operationProgress.setVisibility(View.GONE);
        progressRow.addView(operationProgress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        progressRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(progressRow);
    }

    private void buildScreen() {
        prepareScreen();
        LinearLayout header = row();
        TextView heading = text(getString(R.string.app_name), 24, Color.WHITE);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMinHeight(dp(64));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button reset = button(R.string.templates, view -> { saveDraft(); showTemplates(); });
        header.addView(reset, new LinearLayout.LayoutParams(-2, -2));
        root.addView(header);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column();
        scroll.addView(body);
        body.addView(text(getString(R.string.tagline), 28, Color.WHITE));
        body.addView(text(getString(R.string.intro), 14, MUTED));
        LinearLayout packCard = card(body, R.string.pack);
        packCard.addView(button(R.string.choose_pack, view -> choosePack()));
        LinearLayout selectedPack = row();
        packPreview = new ImageView(this);
        packPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        selectedPack.addView(packPreview, new LinearLayout.LayoutParams(dp(48), dp(48)));
        packInfo = text("", 15, ACCENT);
        packInfo.setPadding(dp(12), dp(6), 0, dp(6));
        selectedPack.addView(packInfo, new LinearLayout.LayoutParams(0, -2, 1));
        packCard.addView(selectedPack);
        LinearLayout identity = card(body, 0);
        title = edit(R.string.title_hint, draft.title);
        filename = edit(R.string.filename_hint, draft.filename);
        title.setSingleLine(true); filename.setSingleLine(true);
        TextView titleLabel = text(getString(R.string.theme_title), 14, MUTED);
        TextView filenameLabel = text(getString(R.string.theme_filename), 14, MUTED);
        title.setId(View.generateViewId());
        filename.setId(View.generateViewId());
        titleLabel.setLabelFor(title.getId());
        filenameLabel.setLabelFor(filename.getId());
        identity.addView(titleLabel); identity.addView(title);
        identity.addView(filenameLabel); identity.addView(filename);
        watch(title, value -> { draft.title = value; saveDraft(); updateExportEnabled(); });
        watch(filename, value -> { draft.filename = value; saveDraft(); updateExportEnabled(); });
        LinearLayout cover = card(body, R.string.cover);
        LinearLayout coverRow = row();
        coverPreview = new ImageView(this);
        coverPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coverPreview.setContentDescription(getString(R.string.cover));
        coverRow.addView(coverPreview, new LinearLayout.LayoutParams(dp(72), dp(128)));
        LinearLayout coverButtons = column();
        coverButtons.addView(button(R.string.choose_cover, view -> openFile(COVER, "image/*")));
        coverButtons.addView(button(R.string.discard_cover, view -> {
            draft.coverPath = ""; saveDraft(); refresh();
        }));
        LinearLayout.LayoutParams coverButtonParams = new LinearLayout.LayoutParams(0, -2, 1);
        coverButtonParams.leftMargin = dp(16);
        coverRow.addView(coverButtons, coverButtonParams);
        cover.addView(coverRow);
        LinearLayout wallpaper = card(body, R.string.wallpaper);
        wallpaper.addView(text(getString(R.string.wallpaper_help), 13, MUTED));
        LinearLayout wallpaperRow = row();
        wallpaperPreview = new ImageView(this);
        wallpaperPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wallpaperPreview.setContentDescription(getString(R.string.wallpaper));
        wallpaperRow.addView(wallpaperPreview, new LinearLayout.LayoutParams(dp(72), dp(128)));
        LinearLayout wallpaperButtons = column();
        wallpaperButtons.addView(button(R.string.choose_wallpaper, view -> openFile(WALLPAPER, "image/*")));
        wallpaperButtons.addView(button(R.string.remove_wallpaper, view -> {
            draft.wallpaperPath = ""; saveDraft(); refresh();
        }));
        LinearLayout.LayoutParams wallpaperParams = new LinearLayout.LayoutParams(0, -2, 1);
        wallpaperParams.leftMargin = dp(16);
        wallpaperRow.addView(wallpaperButtons, wallpaperParams);
        wallpaper.addView(wallpaperRow);
        packCard = card(body, R.string.theme_options);
        packCard.addView(text(getString(R.string.export_scope), 16, Color.WHITE));
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
        packCard.addView(text(getString(R.string.fallback_shape), 16, Color.WHITE));
        LinearLayout shapeRow = row();
        shapePreview = new ImageView(this);
        shapePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        shapePreview.setPadding(dp(8), dp(8), dp(8), dp(8));
        shapePreview.setCropToPadding(true);
        shapeRow.addView(shapePreview, new LinearLayout.LayoutParams(dp(64), dp(64)));
        Spinner shapes = new Spinner(this, Spinner.MODE_DROPDOWN);
        List<String> shapeNames = new ArrayList<>();
        for (IconShape shape : IconShape.values()) shapeNames.add(getString(shape.label));
        ArrayAdapter<String> shapeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, shapeNames) {
            private View label(int position, View convert, boolean dropdown) {
                TextView label = convert instanceof TextView ? (TextView) convert : text("", 16, Color.WHITE);
                label.setText(getItem(position));
                label.setTextColor(Color.WHITE);
                label.setBackgroundColor(dropdown ? PANEL : Color.TRANSPARENT);
                label.setGravity(Gravity.CENTER_VERTICAL);
                label.setPadding(dp(12), dp(12), dp(12), dp(12));
                label.setMinHeight(dp(56));
                label.setMaxLines(2);
                label.setCompoundDrawables(null, null, null, null);
                return label;
            }
            @Override public View getView(int position, View convert, ViewGroup parent) {
                return label(position, convert, false);
            }
            @Override public View getDropDownView(int position, View convert, ViewGroup parent) {
                return label(position, convert, true);
            }
        };
        shapes.setPopupBackgroundDrawable(rounded(PANEL, 8));
        shapes.setAdapter(shapeAdapter);
        shapes.setSelection(draft.shape.ordinal());
        shapes.setContentDescription(getString(R.string.fallback_shape));
        shapes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                draft.shape = IconShape.values()[position]; saveDraft(); refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        shapeRow.addView(shapes, new LinearLayout.LayoutParams(0, -2, 1));
        packCard.addView(shapeRow);
        packCard.addView(text(getString(R.string.fallback_shape_help), 13, MUTED));
        CheckBox folderBackground = new CheckBox(this);
        folderBackground.setText(R.string.folder_background);
        folderBackground.setTextColor(Color.WHITE);
        folderBackground.setChecked(draft.clearFolderBackground);
        folderBackground.setOnCheckedChangeListener((view, checked) -> {
            draft.clearFolderBackground = checked; saveDraft();
        });
        packCard.addView(folderBackground);
        packCard.addView(text(getString(R.string.folder_background_help), 13, MUTED));
        LinearLayout appCard = card(body, R.string.apps);
        appCard.addView(text(getString(R.string.apps_help), 14, MUTED));
        appInfo = text("", 14, ACCENT); appCard.addView(appInfo);
        appCard.addView(button(R.string.apps, view -> {
            if (pack == null) showError(new IOException(getString(R.string.no_pack)));
            else showApps();
        }));
        LinearLayout destination = card(body, R.string.destination);
        destination.addView(text(getString(R.string.destination_help), 14, MUTED));
        destination.addView(button(R.string.choose_destination, view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Honor/Themes"));
            startActivityForResult(intent, DIRECTORY);
        }));
        destinationInfo = text("", 13, ACCENT); destination.addView(destinationInfo);
        templateHelp = text(getString(draft.templateId.isEmpty()
                ? R.string.template_create_help : R.string.template_update_help), 13, MUTED);
        body.addView(templateHelp);
        saveAsNew = new CheckBox(this);
        saveAsNew.setText(R.string.save_as_new_template);
        saveAsNew.setVisibility(draft.templateId.isEmpty() ? View.GONE : View.VISIBLE);
        body.addView(saveAsNew);
        addOperationStatus();
        Button export = button(R.string.export, view -> export());
        exportButton = export;
        export.setTextColor(BG); export.setBackground(buttonSurface(ACCENT, 28));
        export.setMinHeight(dp(56));
        root.addView(export, new LinearLayout.LayoutParams(-1, -2));
        refresh();
    }

    private void refresh() {
        packInfo.setText(pack == null ? getString(R.string.no_pack)
                : getString(R.string.pack_count, pack.label, pack.icons.size()));
        packPreview.setVisibility(pack == null ? View.GONE : View.VISIBLE);
        packPreview.setImageBitmap(pack == null ? null : pack.badge);
        packPreview.setContentDescription(pack == null ? null : pack.label);
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
    }

    private void updateExportEnabled() {
        if (!editing || exportButton == null) return;
        boolean ready = !busy && pack != null && !draft.title.isBlank()
                && !draft.filename.isBlank() && !draft.tree.isBlank();
        if (ready) {
            try { ThemeEngine.filename(draft.filename); }
            catch (IllegalArgumentException error) { ready = false; }
        }
        exportButton.setEnabled(ready);
        exportButton.setAlpha(ready ? 1f : .4f);
    }

    private void refreshShapePreview() {
        IconRepository.Pack currentPack = pack;
        List<IconRepository.App> candidates = apps.stream()
                .filter(app -> currentPack == null || currentPack.automatic(app) == null).toList();
        IconShape shape = draft.shape;
        String key = (currentPack == null ? "" : currentPack.packageName) + ":" + shape;
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

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == ICON_FILE) {
            if (pendingIconApp == null) { showError(new IOException("Выберите приложение для иконки заново.")); return; }
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
                            throw new IOException("Не удалось сохранить обои.");
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
                        if (!fitted.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw new IOException("Не удалось сохранить обложку.");
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
        choosePack(this::selectPack);
    }

    private void choosePack(Consumer<IconRepository.Pack> selected) {
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
        work(repository::packs, packs -> {
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
                        TextView label = text("", 17, Color.WHITE);
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
        if (!draft.pack.isEmpty()) draft.overrides.replaceAll((key, value) ->
                value.isEmpty() || value.contains(":") ? value : IconRepository.reference(draft.pack, value));
        pack = loaded; draft.pack = loaded.packageName;
        if (title.getText().toString().isBlank()) title.setText(loaded.label);
        images.evictAll(); saveDraft(); refresh();
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
        missing.setText(R.string.only_missing); layout.addView(missing);
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
                    labels.addView(text("", 16, Color.WHITE)); labels.addView(text("", 11, MUTED));
                    labels.addView(text("", 12, ACCENT));
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
                bindImage((ImageView) item.getChildAt(0), selected);
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
        IconRepository.Pack[] source = {pack};
        Button sourceButton = button(R.string.choose_pack, view -> {});
        sourceButton.setText(getString(R.string.icon_source, source[0].label));
        layout.addView(sourceButton);
        layout.addView(button(R.string.choose_icon_file, view -> {
            pendingIconApp = app.key();
            pendingIconChanged = changed;
            openFile(ICON_FILE, "image/*");
        }));
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
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = dp(8);
        actionParams.bottomMargin = dp(12);
        layout.addView(actions, actionParams);
        EditText search = edit(R.string.search_icons, ""); search.setSingleLine(true); layout.addView(search);
        TextView count = text("", 13, MUTED); layout.addView(count);
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT); grid.setColumnWidth(dp(96));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        layout.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        List<IconRepository.Icon> filtered = new ArrayList<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int position) { return filtered.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convert, ViewGroup parent) {
                LinearLayout tile;
                if (convert == null) {
                    tile = column(); tile.setGravity(Gravity.CENTER); tile.setPadding(dp(4), dp(8), dp(4), dp(8));
                    ImageView image = new ImageView(MainActivity.this);
                    tile.addView(image, new LinearLayout.LayoutParams(dp(64), dp(64)));
                    TextView name = text("", 10, MUTED); name.setMaxLines(2); name.setGravity(Gravity.CENTER);
                    tile.addView(name, new LinearLayout.LayoutParams(-1, dp(38)));
                } else tile = (LinearLayout) convert;
                IconRepository.Icon icon = filtered.get(position);
                ((TextView) tile.getChildAt(1)).setText(icon.name());
                tile.setContentDescription(icon.name() + " · " + icon.category());
                bindImage((ImageView) tile.getChildAt(0), source[0], icon.name());
                return tile;
            }
        };
        grid.setAdapter(adapter);
        Consumer<String> filter = query -> {
            String q = query.toLowerCase(Locale.ROOT).trim();
            filtered.clear();
            for (IconRepository.Icon icon : source[0].icons.values()) {
                if ((icon.name() + " " + icon.category()).toLowerCase(Locale.ROOT).contains(q)) filtered.add(icon);
            }
            count.setText(filtered.isEmpty() ? getString(R.string.no_icons) : Integer.toString(filtered.size()));
            adapter.notifyDataSetChanged();
        };
        watch(search, filter);
        sourceButton.setOnClickListener(view -> choosePack(loaded -> {
            if (!dialog.isShowing()) return;
            source[0] = loaded;
            sourceButton.setText(getString(R.string.icon_source, loaded.label));
            filter.accept(search.getText().toString());
            grid.smoothScrollToPosition(0);
        }));
        grid.setOnItemClickListener((parent, view, position, id) -> {
            draft.overrides.put(app.key(), IconRepository.reference(source[0].packageName, filtered.get(position).name()));
            changed.run(); dialog.dismiss();
        });
        filter.accept("");
        showFull(dialog);
    }

    private void previewFileIcon(String appKey, Runnable changed, Bitmap image) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        fileIconDialog = dialog;
        LinearLayout content = column();
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        content.setBackground(rounded(PANEL, 24));
        content.addView(text(getString(R.string.file_icon_preview), 22, Color.WHITE));
        content.addView(text(getString(R.string.file_icon_help), 14, MUTED));
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setPadding(dp(16), dp(16), dp(16), dp(16));
        preview.setBackground(rounded(CONTROL, 16));
        preview.setImageBitmap(image);
        preview.setContentDescription(getString(R.string.file_icon_preview));
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
            preview.setImageDrawable(null);
            image.recycle();
            if (fileIconDialog == dialog) fileIconDialog = null;
        });
        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
    }

    private void bindImage(ImageView view, String name) {
        bindImage(view, pack, name);
    }

    private void bindImage(ImageView view, IconRepository.Pack current, String name) {
        String key = name == null ? "" : IconRepository.reference(
                IconRepository.sourcePackage(name, current.packageName), IconRepository.resourceName(name));
        view.setTag(key);
        view.setImageResource(R.drawable.app_icon);
        if (name == null) return;
        Bitmap cached = images.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        thumbnails.execute(() -> {
            try {
                Bitmap bitmap = current.render(name, 128);
                images.put(key, bitmap);
                runOnUiThread(() -> { if (!isDestroyed() && key.equals(view.getTag())) view.setImageBitmap(bitmap); });
            } catch (IOException | RuntimeException error) {
                Log.e("IconManager", "Не удалось показать " + name, error);
                runOnUiThread(() -> {
                    if (!isDestroyed() && key.equals(view.getTag())) {
                        view.setContentDescription("Ошибка ресурса: " + name);
                        if (reportedImageErrors.add(key)) showError(error);
                    }
                });
            }
        });
    }

    private void export() {
        if (title.getText().toString().isBlank() || pack == null || draft.tree.isEmpty()) {
            showError(new IOException(getString(R.string.required))); return;
        }
        String outputName;
        try { outputName = ThemeEngine.filename(filename.getText().toString()); }
        catch (IllegalArgumentException error) { filename.setError(getString(R.string.filename_error)); return; }
        String themeTitle = title.getText().toString().trim();
        if (themeTitle.length() > 100) { title.setError("Не более 100 символов"); return; }
        Uri tree = Uri.parse(draft.tree);
        work(() -> {
            ThemeEngine.validateTree(this, tree);
            return ThemeEngine.findDocument(getContentResolver(), tree, outputName);
        }, existing -> {
            if (existing == null) exportTo(themeTitle, outputName, tree, null);
            else confirmReplacement(outputName, () -> exportTo(themeTitle, outputName, tree, existing));
        });
    }

    AlertDialog confirmReplacement(String outputName, Runnable replace) {
        return new AlertDialog.Builder(this).setTitle(R.string.replace_title)
                .setMessage(getString(R.string.replace_message, outputName))
                .setPositiveButton(R.string.replace, (dialog, which) -> replace.run())
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void exportTo(String themeTitle, String outputName, Uri tree, Uri replace) {
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
            apps = repository.apps();
            Bitmap cover;
            if (coverPath.isEmpty()) cover = ThemeEngine.cover(currentPack, apps, overrides);
            else {
                cover = BitmapFactory.decodeFile(new File(getFilesDir(), coverPath).getPath());
                if (cover == null) throw new IOException("Обложка не читается. Выберите её заново.");
            }
            try {
                ThemeEngine.Result result = ThemeEngine.export(this, themeTitle, outputName, fullPack, shape, clearFolderBackground, currentPack, apps, overrides,
                        cover, tree, replace, snapshot.wallpaperPath.isEmpty() ? null
                                : new File(getFilesDir(), snapshot.wallpaperPath), (done, total) -> {
                            if (done == 1 || done == total || done % 10 == 0) runOnUiThread(() -> {
                                if (!isDestroyed()) {
                                    String message = done == total ? getString(R.string.export_finishing)
                                            : getString(R.string.export_progress, done, total);
                                    status.setText(message);
                                    if (progressDialog != null) progressMessage.setText(message);
                                }
                            });
                        });
                try {
                    return new SavedExport(result, templates.save(snapshot, currentPack.label, asNew));
                } catch (Exception error) {
                    throw new IOException("Тема сохранена в Honor/Themes/" + outputName
                            + ", но шаблон сохранить не удалось. Черновик сохранён; повторите экспорт.\n" + error.getMessage(), error);
                }
            } finally { cover.recycle(); }
        }, saved -> {
            draft.templateId = saved.template().id();
            saveDraft();
            saveAsNew.setChecked(false);
            saveAsNew.setVisibility(View.VISIBLE);
            templateHelp.setText(R.string.template_update_help);
            AlertDialog dialog = showExportResult(saved.theme());
            dialog.setMessage(getString(saved.theme().clearFolderBackground() ? R.string.export_done_full_theme
                    : R.string.export_done, "Honor/Themes/" + saved.theme().filename())
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
        throw new IOException("Приложение «Темы» HONOR не найдено. Откройте его вручную.");
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
        if (busy) { showError(new IOException(getString(R.string.busy))); return; }
        busy = true;
        enabled(root, false);
        operationProgress.setVisibility(View.VISIBLE);
        status.setText(R.string.loading);
        showOperationProgress(message);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            try {
                T result = action.call();
                runOnUiThread(() -> { if (!isDestroyed()) { finishWork(); success.accept(result); } });
            } catch (Exception error) {
                Log.e("IconManager", "Operation failed", error);
                runOnUiThread(() -> { if (!isDestroyed()) { finishWork(); failure.run(); showError(error); } });
            }
        });
    }

    private void finishWork() {
        if (progressDialog != null) { progressDialog.dismiss(); progressDialog = null; }
        busy = false; enabled(root, true); status.setText(R.string.ready);
        updateExportEnabled();
        operationProgress.setVisibility(View.GONE);
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
        progressMessage = text(message, 17, Color.WHITE);
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
    private void showError(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        for (Throwable extra : error.getSuppressed()) message += "\n" + extra.getMessage();
        errorDialog = new AlertDialog.Builder(this).setTitle(R.string.error_title).setMessage(message).show();
    }
    private Dialog fullDialog() { return new Dialog(this, R.style.AppTheme); }
    private LinearLayout dialogLayout(Dialog dialog, String heading) {
        LinearLayout layout = column(); layout.setPadding(dp(16), dp(12), dp(16), dp(12)); layout.setBackgroundColor(BG);
        LinearLayout bar = row();
        TextView label = text(heading, 24, Color.WHITE);
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
        view.setTextSize(16); view.setTextColor(Color.WHITE); view.setHintTextColor(MUTED);
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
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextColor(ACCENT);
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
            TextView label = text(getString(heading), 18, Color.WHITE);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); panel.addView(label);
        }
        return panel;
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
        worker.shutdown(); thumbnails.shutdown(); super.onDestroy();
    }
}
