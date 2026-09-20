package com.qms.notification;

import com.qms.notification.NotificationTemplateRepository.TemplateRow;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Templates per trigger x channel x language (FR-NTF-020): saved as a whole, validated against the trigger's fixed
 * variable set (FR-NTF-021), with a preview against sample values. Reuses {@code config:service_catalogue}
 * (FR-CFG-103) the same way branding and the device fleet already reuse {@code config:org_sites_zones} for an admin
 * area the SRS's own §5.2 table predates, rather than growing the permission matrix for one more configuration screen.
 */
@Service
public class NotificationTemplateService {

    /** Every channel key a template may target, including ones with no adapter registered yet (FR-NTF-005). */
    static final Set<String> ALL_CHANNELS = Set.of("in_app", "staff_alert", "web_push", "email");

    public record Rendered(String subject, String body) {}

    private final NotificationTemplateRepository repository;
    private final Clock clock;

    NotificationTemplateService(NotificationTemplateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    public TemplateRow get(String triggerKey, String channel, String language) {
        NotificationTriggerKey trigger = requireTrigger(triggerKey);
        return repository.find(trigger.key(), channel, language).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    public List<TemplateRow> forTrigger(String triggerKey) {
        return repository.forTrigger(requireTrigger(triggerKey).key());
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    public TemplateRow save(String triggerKey, String channel, String language, String subject, String body, UUID actorId) {
        NotificationTriggerKey trigger = requireTrigger(triggerKey);
        if (!ALL_CHANNELS.contains(channel)) throw fieldError("channel", "unknown_channel");
        if (body == null || body.isBlank()) throw fieldError("body", "required");
        checkVariables("subject", subject, trigger);
        checkVariables("body", body, trigger);
        repository.upsert(trigger.key(), channel, language, subject, body, actorId, clock.instant());
        return repository.find(trigger.key(), channel, language).orElseThrow();
    }

    /** Renders trigger x channel x language against sample values, without saving (FR-NTF-020's "preview"). */
    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    public Rendered preview(String triggerKey, String channel, String language) {
        NotificationTriggerKey trigger = requireTrigger(triggerKey);
        TemplateRow row = repository.find(trigger.key(), channel, language).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Map<String, String> sample = sampleValues(trigger);
        return new Rendered(NotificationRenderer.render(row.subject(), sample), NotificationRenderer.render(row.body(), sample));
    }

    /** What {@link NotificationDispatcher} calls: the rendered content for one real message, or empty when no template exists. */
    Optional<Rendered> render(String triggerKey, String channel, String language, Map<String, String> values) {
        return repository.find(triggerKey, channel, language)
                .map(row -> new Rendered(NotificationRenderer.render(row.subject(), values), NotificationRenderer.render(row.body(), values)));
    }

    private void checkVariables(String field, String text, NotificationTriggerKey trigger) {
        Set<String> unknown = new java.util.LinkedHashSet<>(NotificationRenderer.variablesIn(text));
        unknown.removeAll(trigger.variables());
        if (!unknown.isEmpty()) throw fieldError(field, "unknown_variable:" + String.join(",", unknown));
    }

    private static Map<String, String> sampleValues(NotificationTriggerKey trigger) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("token_number", "A-042");
        values.put("service_group_name", "Outpatient");
        values.put("service_name", "Consultation");
        values.put("counter_label", "Counter 3");
        values.put("site_name", "Main campus");
        values.put("date", "2026-09-21");
        values.put("time", "09:00");
        values.keySet().retainAll(trigger.variables());
        return values;
    }

    static NotificationTriggerKey requireTrigger(String triggerKey) {
        return NotificationTriggerKey.fromKey(triggerKey).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static ApiException fieldError(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
