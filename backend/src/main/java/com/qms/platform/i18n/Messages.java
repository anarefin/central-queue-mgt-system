package com.qms.platform.i18n;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * Looks up localised text from the language packs. A missing or blank translation falls back to the Site default
 * language and then the system default, and never yields a raw key or an empty string (FR-I18N-011).
 *
 * <p>Packs are {@code i18n/messages_<lang>.properties} in the jar (en, bn) plus an optional external directory that
 * adds languages or overrides wording without a code release (FR-I18N-001).
 */
@Component
public class Messages {

    static final String FALLBACK_KEY = "fallback.text";
    private static final String LAST_RESORT = "Text unavailable";

    private final LanguageProperties properties;
    private final SiteDefaultLanguage siteDefault;
    private final ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();

    public Messages(LanguageProperties properties, SiteDefaultLanguage siteDefault) {
        this.properties = properties;
        this.siteDefault = siteDefault;
        List<String> basenames = new ArrayList<>();
        if (properties.packDir() != null && !properties.packDir().isBlank()) {
            basenames.add("file:" + properties.packDir() + "/messages"); // first, so client overrides win
        }
        basenames.add("classpath:i18n/messages");
        source.setBasenames(basenames.toArray(String[]::new));
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        source.setCacheSeconds(30);
    }

    public String text(String key, String language, Object... args) {
        for (String candidate : fallbackChain(language)) {
            String found = lookup(key, candidate, args);
            if (found != null) {
                return found;
            }
        }
        String generic = lookup(FALLBACK_KEY, properties.systemDefaultLanguage(), new Object[0]);
        return generic != null ? generic : LAST_RESORT;
    }

    /** The text in every enabled language, for the {@code message_i18n} member of the error envelope. */
    public Map<String, String> allLanguages(String key, Object... args) {
        Map<String, String> all = new LinkedHashMap<>();
        for (String language : properties.languages()) {
            all.put(language, text(key, language, args));
        }
        return all;
    }

    private List<String> fallbackChain(String language) {
        var chain = new LinkedHashSet<String>();
        if (language != null && !language.isBlank()) {
            chain.add(language);
        }
        chain.add(siteDefault.get().orElse(properties.systemDefaultLanguage()));
        chain.add(properties.systemDefaultLanguage());
        return List.copyOf(chain);
    }

    private String lookup(String key, String language, Object[] args) {
        String value = source.getMessage(key, args, null, Locale.forLanguageTag(language));
        return value == null || value.isBlank() ? null : value;
    }
}
