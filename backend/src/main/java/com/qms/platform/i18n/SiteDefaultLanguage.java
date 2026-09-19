package com.qms.platform.i18n;

import java.util.Optional;

/** Source of the Site default language (FR-I18N-002). Ticket 05 supplies a Site-backed implementation. */
@FunctionalInterface
public interface SiteDefaultLanguage {

    Optional<String> get();
}
