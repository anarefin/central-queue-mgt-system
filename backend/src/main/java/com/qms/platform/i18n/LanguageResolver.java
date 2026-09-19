package com.qms.platform.i18n;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Picks the language for a request: user/visitor preference → device setting → site default → system default,
 * most specific winning (FR-I18N-003). A candidate that is not an enabled language is skipped, not returned.
 */
@Component
public class LanguageResolver {

    private final LanguageProperties properties;
    private final SiteDefaultLanguage siteDefault;

    public LanguageResolver(LanguageProperties properties, SiteDefaultLanguage siteDefault) {
        this.properties = properties;
        this.siteDefault = siteDefault;
    }

    public String resolve(String userPreference, String deviceLanguage) {
        return Stream.of(userPreference, deviceLanguage, siteDefault.get().orElse(null), properties.systemDefaultLanguage())
                .map(this::enabledOrNull)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(properties.systemDefaultLanguage());
    }

    /** The first enabled language in an {@code Accept-Language} header, highest quality first. */
    public Optional<String> fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(header);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        return ranges.stream()
                .sorted(Comparator.comparingDouble(Locale.LanguageRange::getWeight).reversed())
                .map(range -> enabledOrNull(range.getRange()))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    public List<String> enabledLanguages() {
        return properties.languages();
    }

    private String enabledOrNull(String tag) {
        if (tag == null || tag.isBlank() || "*".equals(tag.trim())) {
            return null;
        }
        String language = Locale.forLanguageTag(tag.trim()).getLanguage();
        return properties.languages().contains(language) ? language : null;
    }
}
