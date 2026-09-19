package com.qms.platform.i18n;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param systemDefaultLanguage last-resort language; its pack must be complete
 * @param siteDefaultLanguage   stand-in for the Site default until sites exist (ticket 05); blank means "use system default"
 * @param languages             enabled language codes; en and bn ship in the jar, others come from {@code packDir}
 * @param packDir               optional directory of {@code messages_<lang>.properties} files that add languages or
 *                              override shipped wording without a code release (FR-I18N-001)
 */
@ConfigurationProperties("qms.i18n")
public record LanguageProperties(
        @DefaultValue("en") String systemDefaultLanguage,
        String siteDefaultLanguage,
        @DefaultValue({"en", "bn"}) List<String> languages,
        String packDir) {}
