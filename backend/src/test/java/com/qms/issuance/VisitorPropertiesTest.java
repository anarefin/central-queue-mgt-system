package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-SEC-023: which optional walk-in fields registration captures is closed configuration, validated at startup. */
class VisitorPropertiesTest {

    @Test
    void acceptsTheKnownOptionalFields() {
        VisitorProperties properties = new VisitorProperties(Duration.ofSeconds(1), List.of("email", "purpose"));

        assertThat(properties.captures("email")).isTrue();
        assertThat(properties.captures("purpose")).isTrue();
        assertThat(properties.captures("category")).isFalse();
    }

    @Test
    void rejectsAnUnknownField() {
        assertThatThrownBy(() -> new VisitorProperties(Duration.ofSeconds(1), List.of("phone_number")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phone_number");
    }

    @Test
    void anEmptyListCapturesNoOptionalField() {
        VisitorProperties properties = new VisitorProperties(Duration.ofSeconds(1), List.of());

        assertThat(properties.captures("email")).isFalse();
        assertThat(properties.captures("category")).isFalse();
        assertThat(properties.captures("purpose")).isFalse();
    }

    // ---- FR-SEC-023 at the point registration actually applies it (VisitorService.capture) -----------------------

    private static RegisterVisitorRequest request() {
        return new RegisterVisitorRequest("  Amina Rahman  ", " 01700000000 ", "amina@example.com", "vip", "wheelchair access");
    }

    @Test
    void aFieldTurnedOnIsCapturedAndTrimmed() {
        VisitorProperties allOn = new VisitorProperties(Duration.ofSeconds(1), List.of("email", "category", "purpose"));

        VisitorService.Captured captured = VisitorService.capture(request(), allOn);

        assertThat(captured.name()).isEqualTo("Amina Rahman");
        assertThat(captured.phone()).isEqualTo("01700000000");
        assertThat(captured.email()).isEqualTo("amina@example.com");
        assertThat(captured.category()).isEqualTo("vip");
        assertThat(captured.purpose()).isEqualTo("wheelchair access");
    }

    @Test
    void aFieldTurnedOffIsNeitherCapturedNorRetainedEvenWhenTheRequestSendsIt() {
        VisitorProperties categoryOnly = new VisitorProperties(Duration.ofSeconds(1), List.of("category"));

        VisitorService.Captured captured = VisitorService.capture(request(), categoryOnly);

        assertThat(captured.name()).isEqualTo("Amina Rahman");
        assertThat(captured.phone()).isEqualTo("01700000000");
        assertThat(captured.category()).isEqualTo("vip");
        assertThat(captured.email()).isNull();
        assertThat(captured.purpose()).isNull();
    }

    @Test
    void nameAndPhoneAreAlwaysCapturedRegardlessOfConfiguration() {
        VisitorProperties nothingOptional = new VisitorProperties(Duration.ofSeconds(1), List.of());

        VisitorService.Captured captured = VisitorService.capture(request(), nothingOptional);

        assertThat(captured.name()).isEqualTo("Amina Rahman");
        assertThat(captured.phone()).isEqualTo("01700000000");
        assertThat(captured.email()).isNull();
        assertThat(captured.category()).isNull();
        assertThat(captured.purpose()).isNull();
    }

    @Test
    void aBlankOptionalFieldCapturesAsNullEvenWhenTurnedOn() {
        VisitorProperties allOn = new VisitorProperties(Duration.ofSeconds(1), List.of("email", "category", "purpose"));
        RegisterVisitorRequest blankExtras = new RegisterVisitorRequest("Amina", "01700000000", "  ", "", null);

        VisitorService.Captured captured = VisitorService.capture(blankExtras, allOn);

        assertThat(captured.email()).isNull();
        assertThat(captured.category()).isNull();
        assertThat(captured.purpose()).isNull();
    }
}
