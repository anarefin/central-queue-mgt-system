package com.qms.integration.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-INT-020: an endpoint's description, URL (behind {@link WebhookEndpointSecurity}) and its subscribed event
 * types, which must come from §21.4's closed set ({@link WebhookEventType}). */
class WebhookEndpointRulesTest {

    @Test
    void aBlankOrOverlongDescriptionIsRejected() {
        assertThatThrownBy(() -> WebhookEndpointRules.description(" "))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> WebhookEndpointRules.description("x".repeat(201))).isInstanceOf(ApiException.class);
    }

    @Test
    void descriptionIsTrimmed() {
        assertThat(WebhookEndpointRules.description("  Billing system  ")).isEqualTo("Billing system");
    }

    @Test
    void anUnsafeUrlIsRejectedAsValidationFailed() {
        assertThatThrownBy(() -> WebhookEndpointRules.url("http://127.0.0.1/hooks", false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void aSafeUrlIsAccepted() {
        assertThat(WebhookEndpointRules.url(" https://203.0.113.10/hooks ", false)).isEqualTo("https://203.0.113.10/hooks");
    }

    @Test
    void anEmptyOrUnknownEventTypeListIsRejected() {
        assertThatThrownBy(() -> WebhookEndpointRules.eventTypes(List.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> WebhookEndpointRules.eventTypes(List.of("not.a.real.type"))).isInstanceOf(ApiException.class);
    }

    @Test
    void everyKnownEventTypeIsAcceptedAndDuplicatesAreDropped() {
        assertThat(WebhookEndpointRules.eventTypes(List.of("ticket.called", "ticket.called", "session.opened")))
                .containsExactly("ticket.called", "session.opened");
    }
}
