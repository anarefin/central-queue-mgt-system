package com.qms.platform.alerts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a bounded context hands the threshold-alert pipeline when its own sweep finds a breach (SRS §15.4, §11.3
 * FR-AGT-023, ticket 47): enough to group repeated breaches (FR-MON-023), notify and audit, without the pipeline
 * depending on the caller's own tables (the same shape {@code NotificationContext} already gives the notification
 * pipeline).
 *
 * @param siteId the Site the breach belongs to
 * @param serviceId the Service the threshold was configured against, or null for one with none of its own (a break
 *     overrun belongs to an Agent's session, not a Service)
 * @param thresholdType one of {@link ThresholdAlertTypes}
 * @param subjectId disambiguates within (site, service, thresholdType) where more than one subject could be
 *     breaching it at once (a break overrun's own counter session); null where the key is already specific enough
 * @param measuredValue the value that breached, in the threshold's own unit
 * @param thresholdValue the configured limit it breached
 * @param occurredAt when the sweep observed the breach
 */
public record ThresholdAlertContext(
        UUID siteId, UUID serviceId, String thresholdType, UUID subjectId, BigDecimal measuredValue, BigDecimal thresholdValue, Instant occurredAt) {}
