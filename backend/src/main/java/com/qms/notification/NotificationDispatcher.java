package com.qms.notification;

import com.qms.platform.Profiles;
import com.qms.platform.i18n.Messages;
import com.qms.platform.notifications.NotificationContext;
import com.qms.platform.notifications.NotificationTrigger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seam's one implementation (SRS §14, ticket 38): resolves whether a trigger is enabled for the Site/Service,
 * the visitor's language (FR-NTF-022), quiet hours (FR-NTF-031) and throttling (FR-NTF-030) and consent
 * (FR-NTF-035), renders the first channel's template, and queues exactly one row — a single insert that joins the
 * caller's own transaction and never sends anything itself (FR-NTF-003). {@link NotificationSendScheduler} does the
 * sending, off this thread entirely.
 */
@Component
@Profile(Profiles.SERVING)
class NotificationDispatcher implements NotificationTrigger {

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final NotificationTriggerConfigService triggerConfig;
    private final NotificationTemplateService templates;
    private final NotificationConsentService consent;
    private final NotificationMessageRepository messages;
    private final NotificationProperties properties;
    private final Messages i18n;
    private final Clock clock;

    NotificationDispatcher(
            JdbcTemplate jdbc,
            JsonMapper mapper,
            NotificationTriggerConfigService triggerConfig,
            NotificationTemplateService templates,
            NotificationConsentService consent,
            NotificationMessageRepository messages,
            NotificationProperties properties,
            Messages i18n,
            Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.triggerConfig = triggerConfig;
        this.templates = templates;
        this.consent = consent;
        this.messages = messages;
        this.properties = properties;
        this.i18n = i18n;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void fire(String triggerKey, NotificationContext context) {
        Optional<NotificationTriggerKey> maybeTrigger = NotificationTriggerKey.fromKey(triggerKey);
        if (maybeTrigger.isEmpty() || context.siteId() == null) return;
        NotificationTriggerKey trigger = maybeTrigger.get();
        Instant now = context.occurredAt() != null ? context.occurredAt() : clock.instant();

        Facts facts = loadFacts(context);
        var effective = triggerConfig.effective(trigger, context.siteId(), context.serviceId(), facts.originChannel());
        if (!effective.enabled() || effective.channelOrder().isEmpty()) return;

        boolean essential = trigger.essential();
        if (!essential && consent.isOptedOut(context.visitorId())) return;

        String language = resolveLanguage(facts.visitorLanguage(), facts.siteDefaultLanguage());
        Map<String, String> variables = buildVariables(trigger, facts, context, language);
        String firstChannel = effective.channelOrder().get(0);
        Optional<NotificationTemplateService.Rendered> rendered = templates.render(trigger.key(), firstChannel, language, variables);
        if (rendered.isEmpty()) {
            messages.insertSuppressed(
                    trigger.key(), effective.channelOrder(), language, essential, context.siteId(), context.serviceId(), context.ticketId(),
                    context.visitorId(), variables, null, null, "no_template", now);
            return;
        }

        if (!essential && withinQuietHours(facts.quietStart(), facts.quietEnd(), facts.timezone(), now)) {
            messages.insertSuppressed(
                    trigger.key(), effective.channelOrder(), language, essential, context.siteId(), context.serviceId(), context.ticketId(),
                    context.visitorId(), variables, rendered.get().subject(), rendered.get().body(), "quiet_hours", now);
            return;
        }
        if (context.ticketId() != null && messages.countForTicket(context.ticketId()) >= properties.maxPerTicket()) {
            messages.insertSuppressed(
                    trigger.key(), effective.channelOrder(), language, essential, context.siteId(), context.serviceId(), context.ticketId(),
                    context.visitorId(), variables, rendered.get().subject(), rendered.get().body(), "throttled_ticket", now);
            return;
        }
        if (context.visitorId() != null && messages.countForVisitorToday(context.visitorId(), facts.timezone(), now) >= properties.maxPerDay()) {
            messages.insertSuppressed(
                    trigger.key(), effective.channelOrder(), language, essential, context.siteId(), context.serviceId(), context.ticketId(),
                    context.visitorId(), variables, rendered.get().subject(), rendered.get().body(), "throttled_daily", now);
            return;
        }

        messages.insertQueued(
                trigger.key(), effective.channelOrder(), language, essential, context.siteId(), context.serviceId(), context.ticketId(),
                context.visitorId(), variables, rendered.get().subject(), rendered.get().body(), now);
    }

    /** Visitor preference falling back to the Site default (FR-NTF-022). */
    private static String resolveLanguage(String visitorLanguage, String siteDefaultLanguage) {
        return visitorLanguage != null && !visitorLanguage.isBlank() ? visitorLanguage : siteDefaultLanguage;
    }

    /** True when {@code now}, in the Site's own timezone, falls in a configured quiet-hours window (FR-NTF-031). A
     * window that wraps past midnight ({@code start > end}) is honoured as the two segments it spans. */
    static boolean withinQuietHours(LocalTime start, LocalTime end, ZoneId zone, Instant now) {
        if (start == null || end == null) return false;
        LocalTime local = ZonedDateTime.ofInstant(now, zone).toLocalTime();
        if (start.isBefore(end)) return !local.isBefore(start) && local.isBefore(end);
        return !local.isBefore(start) || local.isBefore(end);
    }

    private Map<String, String> buildVariables(NotificationTriggerKey trigger, Facts facts, NotificationContext context, String language) {
        Map<String, String> values = new LinkedHashMap<>();
        // FR-SEC-021, ticket 54: a clinical-sensitivity Site never sends a real service/service-group name in a
        // notification, replaced with the same neutral label key the Site's own displays and announcements show
        // (device.DisplayStateReads.NEUTRAL_LABEL_KEY, duplicated as a literal rather than a cross-package constant
        // since the two contexts otherwise have nothing to do with each other).
        String neutralLabel = facts.clinical() ? i18n.text("privacy.neutralService", language) : null;
        values.put("token_number", context.tokenNumber());
        values.put("service_group_name", facts.clinical() ? neutralLabel : facts.serviceGroupName());
        values.put("service_name", facts.includeServiceName() ? (facts.clinical() ? neutralLabel : facts.serviceName()) : "");
        values.put("counter_label", facts.counterLabel());
        values.put("site_name", facts.siteName());
        // FR-APT-050 (ticket 40): an appointment trigger's own slot, already formatted by its caller; null (and so
        // dropped below) for every queue-side trigger, which has none.
        values.put("date", context.date());
        values.put("time", context.time());
        values.values().removeIf(java.util.Objects::isNull);
        values.keySet().retainAll(trigger.variables());
        return values;
    }

    private record Facts(
            String siteName,
            ZoneId timezone,
            LocalTime quietStart,
            LocalTime quietEnd,
            String siteDefaultLanguage,
            String serviceGroupName,
            String serviceName,
            boolean includeServiceName,
            String counterLabel,
            String originChannel,
            String visitorLanguage,
            boolean clinical) {}

    private record SiteRow(
            String name, ZoneId timezone, LocalTime quietStart, LocalTime quietEnd, String defaultLanguage, boolean clinicalSensitivity) {}

    private record ServiceRow(String nameI18n, String groupNameI18n, boolean includeServiceName) {}

    private Facts loadFacts(NotificationContext context) {
        SiteRow site = jdbc.query(
                        "SELECT name, timezone, quiet_hours_start, quiet_hours_end, default_language, clinical_sensitivity FROM site WHERE id = ?",
                        (rs, i) -> new SiteRow(
                                rs.getString("name"), ZoneId.of(rs.getString("timezone")), rs.getObject("quiet_hours_start", LocalTime.class),
                                rs.getObject("quiet_hours_end", LocalTime.class), rs.getString("default_language"), rs.getBoolean("clinical_sensitivity")),
                        context.siteId())
                .stream().findFirst().orElseThrow();

        String serviceGroupName = null;
        String serviceName = null;
        boolean includeServiceName = true;
        if (context.serviceId() != null) {
            ServiceRow service = jdbc.query(
                            "SELECT s.name_i18n, s.include_service_name_in_notifications, g.name_i18n AS group_name_i18n"
                                    + " FROM service s JOIN service_group g ON g.id = s.service_group_id WHERE s.id = ?",
                            (rs, i) -> new ServiceRow(rs.getString("name_i18n"), rs.getString("group_name_i18n"), rs.getBoolean("include_service_name_in_notifications")),
                            context.serviceId())
                    .stream().findFirst().orElseThrow();
            includeServiceName = service.includeServiceName();
            serviceName = localisedName(service.nameI18n(), site.defaultLanguage());
            serviceGroupName = localisedName(service.groupNameI18n(), site.defaultLanguage());
        }

        String counterLabel = context.counterId() == null
                ? null
                : jdbc.query("SELECT label FROM counter WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, context.counterId());
        String originChannel = context.ticketId() == null
                ? null
                : jdbc.query("SELECT origin_channel FROM ticket WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, context.ticketId());
        String visitorLanguage = context.visitorId() == null
                ? null
                : jdbc.query("SELECT preferred_language FROM visitor WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, context.visitorId());

        return new Facts(
                site.name(), site.timezone(), site.quietStart(), site.quietEnd(), site.defaultLanguage(),
                serviceGroupName, serviceName, includeServiceName, counterLabel, originChannel, visitorLanguage, site.clinicalSensitivity());
    }

    @SuppressWarnings("unchecked")
    private String localisedName(String nameI18nJson, String defaultLanguage) {
        if (nameI18nJson == null) return null;
        Map<String, String> names = mapper.readValue(nameI18nJson, Map.class);
        String value = names.get(defaultLanguage);
        return value != null ? value : names.values().stream().findFirst().orElse(null);
    }
}
