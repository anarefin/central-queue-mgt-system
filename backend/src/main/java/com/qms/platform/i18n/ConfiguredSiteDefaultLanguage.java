package com.qms.platform.i18n;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** Config-backed Site default, used until Sites exist. Ticket 05 replaces it with a Site-backed bean. */
@Component
public class ConfiguredSiteDefaultLanguage implements SiteDefaultLanguage {

    private final LanguageProperties properties;

    public ConfiguredSiteDefaultLanguage(LanguageProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> get() {
        String configured = properties.siteDefaultLanguage();
        return configured == null || configured.isBlank() ? Optional.empty() : Optional.of(configured);
    }
}
