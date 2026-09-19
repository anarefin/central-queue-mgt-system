package com.qms.platform.i18n;

import java.util.Optional;

/**
 * The signed-in user's stored language preference, the most specific level of FR-I18N-003. It cannot travel in the
 * access token because API-011 fixes the claim set, so the identity context supplies it from the user record.
 */
@FunctionalInterface
public interface UserLanguagePreference {

    Optional<String> current();
}
