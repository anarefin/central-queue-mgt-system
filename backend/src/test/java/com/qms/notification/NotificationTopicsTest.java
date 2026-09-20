package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.qms.platform.ApiException;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who owns the {@code staff_alert} topic and what an unauthenticated subscriber gets (FR-QUE-080), no database needed. */
class NotificationTopicsTest {

    private final NotificationTopics topics = new NotificationTopics(new ScopeGuard(new CurrentUser()));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void handlesOnlyStaffAlertTopics() {
        assertThat(topics.handles(Topics.staffAlert(UUID.randomUUID()))).isTrue();
        assertThat(topics.handles(Topics.queue(UUID.randomUUID()))).isFalse();
    }

    @Test
    void anUnauthenticatedSubscriberIsRefused() {
        assertThatThrownBy(() -> topics.authorize(Topics.staffAlert(UUID.randomUUID())))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ApiException.class))
                .extracting(ApiException::code)
                .isEqualTo(com.qms.platform.ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void aMalformedTopicIsAValidationFailureNotAServerError() {
        assertThatThrownBy(() -> topics.authorize(Topics.STAFF_ALERT + "not-a-uuid"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ApiException.class))
                .extracting(ApiException::code)
                .isEqualTo(com.qms.platform.ErrorCode.VALIDATION_FAILED);
    }
}
