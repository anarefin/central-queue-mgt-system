package com.qms.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param primaryLinkToleranceMinutes how many minutes of score a fallback (higher-weight) Counter link may trail the best
 *     ticket by and still lose to a primary link (FR-QUE-030); 0 means the highest score always wins
 */
@ConfigurationProperties("qms.queue")
public record QueueProperties(@DefaultValue("5") double primaryLinkToleranceMinutes) {

    public QueueProperties {
        if (primaryLinkToleranceMinutes < 0) throw new IllegalArgumentException("qms.queue.primary-link-tolerance-minutes must not be negative");
    }
}
