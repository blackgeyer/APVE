package org.apve;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class LocalizationManager {

    private final apve plugin;

    public LocalizationManager(apve plugin) {
        this.plugin = plugin;
    }

    public void applyLocalization() {
        YamlConfiguration currentConfig = (YamlConfiguration) plugin.getConfig();
        String langSetting = currentConfig.getString("language", "auto");

        String targetLocaleFile;
        if (langSetting.equalsIgnoreCase("ru") || langSetting.equalsIgnoreCase("ru_RU")) {
            targetLocaleFile = "ru_RU.yml";
        } else if (langSetting.equalsIgnoreCase("en") || langSetting.equalsIgnoreCase("en_US")) {
            targetLocaleFile = "en_US.yml";
        } else {
            String systemLanguage = Locale.getDefault().getLanguage();
            targetLocaleFile = systemLanguage.equalsIgnoreCase("ru") ? "ru_RU.yml" : "en_US.yml";
        }

        String lastAppliedLang = currentConfig.getString("internal.last-applied-language", "");
        if (lastAppliedLang.equals(targetLocaleFile)) {
            return;
        }

        InputStream stream = plugin.getResource(targetLocaleFile);
        YamlConfiguration localeConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        );

        for (String key : localeConfig.getKeys(true)) {
            if (localeConfig.isConfigurationSection(key)) {
                continue;
            }
            Object localizedValue = localeConfig.get(key);
            currentConfig.set(key, localizedValue);
        }

        currentConfig.set("internal.last-applied-language", targetLocaleFile);
        plugin.saveConfig();
        plugin.getLogger().info("Localization sucessfully overwritten: " + targetLocaleFile);
    }
}
