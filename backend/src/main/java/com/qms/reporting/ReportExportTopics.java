package com.qms.reporting;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.realtime.TopicSource;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may subscribe to {@code report-export:{user_id}} (ticket 49, FR-RPT-004): only that user — unlike every other
 * topic in §21.2, this one names its own subscriber rather than a Site or a Service group, since it carries one
 * person's own background export job finishing, never anything a wider audience should see.
 */
@Component
class ReportExportTopics implements TopicSource {

    private final CurrentUser currentUser;

    ReportExportTopics(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public boolean handles(String topic) {
        return topic.startsWith(Topics.REPORT_EXPORT);
    }

    @Override
    public void authorize(String topic) {
        UUID owner = id(topic);
        if (!owner.equals(currentUser.require().userId())) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    @Override
    public Map<String, Object> snapshot(String topic) {
        return Map.of();
    }

    private static UUID id(String topic) {
        try {
            return UUID.fromString(topic.substring(Topics.REPORT_EXPORT.length()));
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "topic", "code", "invalid"))));
        }
    }
}
