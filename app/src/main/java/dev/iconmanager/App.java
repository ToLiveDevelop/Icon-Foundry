package dev.iconmanager;

import android.app.Application;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

public final class App extends Application {
    private static App instance;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        var preferences = getSharedPreferences("settings", 0);
        LocaleManager locales = getSystemService(LocaleManager.class);
        if (!preferences.contains("language")) {
            String language = initialLanguage(locales.getSystemLocales());
            if (!preferences.edit().putString("language", language).commit()) {
                throw new IllegalStateException(getString(R.string.settings_save_error));
            }
            locales.setApplicationLocales(LocaleList.forLanguageTags(language));
        } else if (locales.getApplicationLocales().isEmpty()) {
            locales.setApplicationLocales(LocaleList.forLanguageTags(preferences.getString("language", "en")));
        }
    }

    static String initialLanguage(LocaleList system) {
        for (int i = 0; i < system.size(); i++) {
            String language = system.get(i).getLanguage();
            if ("ru".equals(language) || "en".equals(language)) return language;
        }
        return "en";
    }

    static Context themed(Context base) {
        String theme = base.getSharedPreferences("settings", 0).getString("theme", "system");
        if ("system".equals(theme)) return base;
        Configuration configuration = new Configuration();
        configuration.uiMode = "dark".equals(theme) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        return base.createConfigurationContext(configuration);
    }

    static String text(int resource, Object... args) {
        return instance.getString(resource, args);
    }
}
