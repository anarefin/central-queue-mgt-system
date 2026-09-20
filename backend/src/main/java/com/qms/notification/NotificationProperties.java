package com.qms.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxPerTicket the most non-suppressed messages one ticket may receive (FR-NTF-030, default 4)
 * @param maxPerDay the most non-suppressed messages one visitor may receive in a Site-local day, across every ticket
 *     (FR-NTF-030, default 10)
 * @param maxAttemptsPerChannel how many times a message retries on the same channel, with exponential backoff,
 *     before falling to the next channel in its order (FR-NTF-033, default 3)
 * @param backoffBaseSeconds the backoff before a retry's Nth attempt on the same channel: {@code backoffBaseSeconds *
 *     2^(attempt - 1)} (FR-NTF-033, default 30 seconds)
 * @param sendPollCron when {@link NotificationSendScheduler} sweeps due messages; {@code -} disables it so a test can
 *     drive the sweep itself, the same convention {@code qms.appointment.no-show-check-cron} uses
 * @param consentTextVersion the version recorded against a visitor's opt-out consent when none is given explicitly
 *     (FR-SEC-030)
 */
@ConfigurationProperties("qms.notification")
public record NotificationProperties(
        @DefaultValue("4") int maxPerTicket,
        @DefaultValue("10") int maxPerDay,
        @DefaultValue("3") int maxAttemptsPerChannel,
        @DefaultValue("30") int backoffBaseSeconds,
        @DefaultValue("*/5 * * * * *") String sendPollCron,
        @DefaultValue("v1") String consentTextVersion) {}
