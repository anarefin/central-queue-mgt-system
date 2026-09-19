package com.qms.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param primaryLinkToleranceMinutes how many minutes of score a fallback (higher-weight) Counter link may trail the best
 *     ticket by and still lose to a primary link (FR-QUE-030); 0 means the highest score always wins
 * @param announceRepeatLimit how many times an Agent may Re-announce one call (FR-DSP-028); 0 switches Re-announce off
 * @param missLimit how many Misses a ticket may take back into the queue; the Miss after this many closes it as
 *     {@code no_show} (FR-QUE-050)
 * @param missReentryPosition where a missed ticket re-enters the queue (FR-QUE-051)
 * @param missReentryAfter for {@link ReentryPosition#AFTER_N}: how many tickets stay ahead of the missed one
 * @param holdLimit how many tickets one session may hold at once (FR-AGT-013); 0 switches Hold off
 * @param callTimeoutSeconds how long a called ticket may wait for its Agent to act before the Agent is prompted and may return it to the
 *     queue (FR-QUE-032); 0 switches the timeout off
 * @param transferHeadstartMinutes the Head start a Successor ticket gets when it is transferred, in minutes; not set means the
 *     predecessor's accrued wait, so the visitor is not sent to the back (FR-QUE-053)
 */
@ConfigurationProperties("qms.queue")
public record QueueProperties(
        @DefaultValue("5") double primaryLinkToleranceMinutes,
        @DefaultValue("3") int announceRepeatLimit,
        @DefaultValue("2") int missLimit,
        @DefaultValue("after-n") ReentryPosition missReentryPosition,
        @DefaultValue("3") int missReentryAfter,
        @DefaultValue("3") int holdLimit,
        @DefaultValue("90") int callTimeoutSeconds,
        Integer transferHeadstartMinutes) {

    public QueueProperties {
        if (primaryLinkToleranceMinutes < 0) throw new IllegalArgumentException("qms.queue.primary-link-tolerance-minutes must not be negative");
        if (announceRepeatLimit < 0) throw new IllegalArgumentException("qms.queue.announce-repeat-limit must not be negative");
        if (missLimit < 0) throw new IllegalArgumentException("qms.queue.miss-limit must not be negative");
        if (missReentryAfter < 1) throw new IllegalArgumentException("qms.queue.miss-reentry-after must be at least 1");
        if (holdLimit < 0) throw new IllegalArgumentException("qms.queue.hold-limit must not be negative");
        if (callTimeoutSeconds < 0) throw new IllegalArgumentException("qms.queue.call-timeout-seconds must not be negative");
        if (transferHeadstartMinutes != null && transferHeadstartMinutes < 0) throw new IllegalArgumentException("qms.queue.transfer-headstart-minutes must not be negative");
    }
}
