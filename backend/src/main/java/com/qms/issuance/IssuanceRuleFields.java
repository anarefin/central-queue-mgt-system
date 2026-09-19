package com.qms.issuance;

import com.qms.issuance.IssuanceRulesRepository.HoursRow;
import com.qms.issuance.IssuanceRulesRepository.Settings;
import com.qms.issuance.IssuanceRulesViews.HolidayRequest;
import com.qms.issuance.IssuanceRulesRepository.ServiceRule;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Field rules for the issuance rules. Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class IssuanceRuleFields {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);
    static final Set<String> DUPLICATE_POLICIES = Set.of("allow", "warn", "block");
    static final int MAX_CAP = 1_000_000;
    static final int MAX_LIMIT = 100_000;
    private static final int MAX_MESSAGE = 500;
    private static final int MAX_NAME = 100;

    private IssuanceRuleFields() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    static LocalTime time(String field, String value) {
        try {
            return value == null ? null : LocalTime.parse(value, TIME);
        } catch (DateTimeParseException e) {
            throw invalid(field, "Pattern");
        }
    }

    /** A week: each ISO weekday at most once, each opening before it closes. Sorted by weekday. */
    static List<HoursRow> week(List<IssuanceRulesViews.WeekDay> days) {
        if (days == null) throw invalid("days", "NotNull");
        List<HoursRow> rows = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (IssuanceRulesViews.WeekDay day : days) {
            if (day == null || day.weekday() < 1 || day.weekday() > 7) throw invalid("days", "invalid_weekday");
            if (!seen.add(day.weekday())) throw invalid("days", "duplicate_weekday");
            LocalTime open = time("days", day.open());
            LocalTime close = time("days", day.close());
            if (open == null || close == null) throw invalid("days", "NotNull");
            if (!open.isBefore(close)) throw invalid("days", "open_must_precede_close");
            rows.add(new HoursRow(day.weekday(), open, close));
        }
        rows.sort(Comparator.comparingInt(HoursRow::weekday));
        return rows;
    }

    static IssuanceRulesRepository.HolidayRow holiday(java.util.UUID siteId, HolidayRequest request) {
        if (request == null) throw invalid("date", "NotNull");
        LocalDate date;
        try {
            date = request.date() == null ? null : LocalDate.parse(request.date());
        } catch (DateTimeParseException e) {
            throw invalid("date", "Pattern");
        }
        if (date == null) throw invalid("date", "NotNull");
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) throw invalid("name", "NotBlank");
        if (name.length() > MAX_NAME) throw invalid("name", "Size");
        boolean half = Boolean.TRUE.equals(request.halfDay());
        LocalTime close = time("close_time", request.closeTime());
        if (half && close == null) throw invalid("close_time", "NotNull");
        if (!half && close != null) throw invalid("close_time", "only_for_half_day");
        return new IssuanceRulesRepository.HolidayRow(java.util.UUID.randomUUID(), siteId, date, name, half, close);
    }

    /** Cut-offs by channel; a channel that does not exist or a figure outside a day is refused. */
    static Map<String, Integer> cutoffs(Map<String, Integer> given) {
        if (given == null) throw invalid("minutes_before_close", "NotNull");
        Map<String, Integer> kept = new LinkedHashMap<>();
        new java.util.TreeMap<>(given).forEach((channel, minutes) -> {
            if (!Channels.ALL.contains(channel)) throw invalid("minutes_before_close", "unknown_channel");
            if (minutes == null || minutes < 0 || minutes > 1440) throw invalid("minutes_before_close", "Range");
            if (minutes > 0) kept.put(channel, minutes);
        });
        return kept;
    }

    static ServiceRule serviceRule(IssuanceRulesViews.ServiceRule given, Collection<String> installed) {
        if (given == null) return ServiceRule.NONE;
        Integer cap = given.dailyCap();
        if (cap != null && (cap < 1 || cap > MAX_CAP)) throw invalid("daily_cap", "Range");
        String policy = given.duplicatePolicy() == null ? "allow" : given.duplicatePolicy();
        if (!DUPLICATE_POLICIES.contains(policy)) throw invalid("duplicate_policy", "Pattern");
        return new ServiceRule(cap, message("cap_message_i18n", given.capMessageI18n(), installed), policy, Boolean.TRUE.equals(given.requireAgent()));
    }

    static Settings settings(IssuanceRulesViews.Settings given, Collection<String> installed) {
        if (given == null) throw invalid("maintenance_enabled", "NotNull");
        int device = given.deviceLimitPerMinute() == null ? 30 : given.deviceLimitPerMinute();
        int visitor = given.visitorLimitPerHour() == null ? 5 : given.visitorLimitPerHour();
        if (device < 1 || device > MAX_LIMIT) throw invalid("device_limit_per_minute", "Range");
        if (visitor < 1 || visitor > MAX_LIMIT) throw invalid("visitor_limit_per_hour", "Range");
        return new Settings(
                Boolean.TRUE.equals(given.maintenanceEnabled()), message("maintenance_message_i18n", given.maintenanceMessageI18n(), installed), device, visitor);
    }

    /** A per-language message: only installed languages, blank texts dropped. No message at all is allowed: the built-in text is used. */
    static Map<String, String> message(String field, Map<String, String> given, Collection<String> installed) {
        Map<String, String> kept = new LinkedHashMap<>();
        if (given == null) return kept;
        if (!installed.containsAll(given.keySet())) throw invalid(field, "unknown_language");
        for (String language : installed) {
            String text = given.get(language);
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.length() > MAX_MESSAGE) throw invalid(field, "Size");
            if (!trimmed.isEmpty()) kept.put(language, trimmed);
        }
        return kept;
    }
}
