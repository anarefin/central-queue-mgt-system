package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@code {{variable}}} substitution and the variable-extraction save-time validation relies on (FR-NTF-020, FR-NTF-021). */
class NotificationRendererTest {

    @Test
    void substitutesEveryKnownVariable() {
        String rendered = NotificationRenderer.render("Token {{token_number}} at {{counter_label}}", Map.of("token_number", "A-042", "counter_label", "Counter 3"));
        assertThat(rendered).isEqualTo("Token A-042 at Counter 3");
    }

    @Test
    void anUnsuppliedVariableRendersAsEmpty() {
        assertThat(NotificationRenderer.render("Hello {{service_name}}", Map.of())).isEqualTo("Hello ");
    }

    @Test
    void nullTextRendersAsNull() {
        assertThat(NotificationRenderer.render(null, Map.of())).isNull();
    }

    @Test
    void variablesInFindsEveryPlaceholderOnce() {
        assertThat(NotificationRenderer.variablesIn("{{token_number}} for {{service_name}}, again {{token_number}}"))
                .isEqualTo(Set.of("token_number", "service_name"));
    }

    @Test
    void variablesInIgnoresPlainText() {
        assertThat(NotificationRenderer.variablesIn("No variables here")).isEmpty();
    }

    @Test
    void variablesInToleratesInnerWhitespace() {
        assertThat(NotificationRenderer.variablesIn("{{ token_number }}")).containsExactly("token_number");
    }
}
