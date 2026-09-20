package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.notification.NotificationMessageRepository.MessageRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The email adapter's own config gate (ticket 40, FR-INT-040): "clients supply an SMTP relay" (§27.2), and until one
 * is configured this never even tries to connect anywhere. No database or network needed for this one, unlike
 * {@link EmailChannelIT}'s real send.
 */
class EmailChannelTest {

    @Test
    void keyIsEmail() {
        assertThat(channel("").key()).isEqualTo("email");
    }

    @Test
    void aBlankHostFailsCleanWithoutTouchingTheDatabaseOrAnySocket() {
        NotificationChannel.Outcome outcome = channel("").send(messageFor(UUID.randomUUID()));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.providerResponse()).isEqualTo("smtp_not_configured");
    }

    private static EmailChannel channel(String host) {
        return new EmailChannel(null, new NotificationEmailProperties(host, 587, null, null, true, "noreply@qms.local", 5000));
    }

    private static MessageRow messageFor(UUID visitorId) {
        return new MessageRow(
                UUID.randomUUID(), "appointment_confirmed", "email", List.of("email", "web_push"), 0, "en", false, UUID.randomUUID(), UUID.randomUUID(), null, visitorId,
                Map.of(), "Your appointment", "See you then", "queued", 0, Instant.now(), null);
    }
}
