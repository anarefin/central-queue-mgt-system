package com.qms.platform.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.platform.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {

    private static Messages messages(String packDir, List<String> languages, String siteDefault) {
        var props = new LanguageProperties("en", siteDefault, languages, packDir);
        return new Messages(props, () -> Optional.ofNullable(siteDefault));
    }

    private static Properties load(String resource) throws IOException {
        var props = new Properties();
        try (var in = MessagesTest.class.getResourceAsStream(resource)) {
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    @Test
    void shippedPacksAreCompleteAndNonBlank() throws IOException {
        // FR-I18N-001: English and Bangla packs ship complete.
        var en = load("/i18n/messages_en.properties");
        var bn = load("/i18n/messages_bn.properties");
        assertThat(bn.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(en.stringPropertyNames());
        for (String key : en.stringPropertyNames()) {
            assertThat(en.getProperty(key)).as("en:" + key).isNotBlank();
            assertThat(bn.getProperty(key)).as("bn:" + key).isNotBlank();
        }
    }

    @Test
    void everyErrorCodeHasAMessageInEveryShippedLanguage() {
        var messages = messages(null, List.of("en", "bn"), "en");
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(messages.allLanguages(code.messageKey())).as(code.wire()).containsOnlyKeys("en", "bn");
            assertThat(messages.allLanguages(code.messageKey()).values()).allSatisfy(v -> assertThat(v).isNotBlank());
        }
    }

    @Test
    void resolvesInTheRequestedLanguage() {
        var messages = messages(null, List.of("en", "bn"), "en");
        assertThat(messages.text("error.not_found", "bn")).isEqualTo(messages.allLanguages("error.not_found").get("bn"));
        assertThat(messages.text("error.not_found", "bn")).isNotEqualTo(messages.text("error.not_found", "en"));
    }

    @Test
    void missingTranslationFallsBackToSiteDefaultNotRawKey(@TempDir Path dir) throws IOException {
        // FR-I18N-011: an extra pack that lacks a key must fall back, never show the key or an empty string.
        Files.writeString(dir.resolve("messages_ur.properties"), "error.not_found=نہیں ملا\n", StandardCharsets.UTF_8);
        var messages = messages(dir.toString(), List.of("en", "bn", "ur"), "bn");

        String result = messages.text("error.forbidden", "ur");

        assertThat(result).isEqualTo(messages.text("error.forbidden", "bn"));
        assertThat(result).isNotEqualTo("error.forbidden").isNotBlank();
    }

    @Test
    void blankTranslationIsTreatedAsMissing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("messages_bn.properties"), "error.not_found=   \n", StandardCharsets.UTF_8);
        var messages = messages(dir.toString(), List.of("en", "bn"), "en");

        assertThat(messages.text("error.not_found", "bn")).isEqualTo(messages.text("error.not_found", "en"));
    }

    @Test
    void unknownKeyStillNeverReturnsRawKeyOrEmpty() {
        var messages = messages(null, List.of("en", "bn"), "en");
        assertThat(messages.text("no.such.key", "bn")).isNotBlank().isNotEqualTo("no.such.key");
    }

    @Test
    void additionalPackLoadsFromDirectoryWithoutCodeChange(@TempDir Path dir) throws IOException {
        // FR-I18N-001: additional packs can be added without a code release.
        Files.writeString(dir.resolve("messages_ur.properties"), "error.not_found=نہیں ملا\n", StandardCharsets.UTF_8);
        var messages = messages(dir.toString(), List.of("en", "bn", "ur"), "en");

        assertThat(messages.text("error.not_found", "ur")).isEqualTo("نہیں ملا");
    }

    @Test
    void clientOverrideDirectoryWinsOverShippedText(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("messages_en.properties"), "error.not_found=Custom wording\n", StandardCharsets.UTF_8);
        var messages = messages(dir.toString(), List.of("en", "bn"), "en");

        assertThat(messages.text("error.not_found", "en")).isEqualTo("Custom wording");
        assertThat(messages.text("error.forbidden", "en")).isNotBlank();
    }

    @Test
    void messageArgumentsAreFormatted(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("messages_en.properties"), "test.greeting=Hello {0}\n", StandardCharsets.UTF_8);
        var messages = messages(dir.toString(), List.of("en", "bn"), "en");

        assertThat(messages.text("test.greeting", "en", "Rahim")).isEqualTo("Hello Rahim");
    }
}
