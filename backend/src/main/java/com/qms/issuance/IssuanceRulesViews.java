package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the issuance-rules endpoints send and receive. Times are {@code HH:mm} in the Site's time zone; dates are {@code yyyy-MM-dd}. */
public final class IssuanceRulesViews {

    private IssuanceRulesViews() {}

    /** One weekday's hours; {@code weekday} is ISO, 1 Monday to 7 Sunday. */
    public record WeekDay(int weekday, String open, String close) {}

    /** A weekly schedule. For a Service an empty list means it follows its Site's hours (FR-CFG-020). */
    public record Hours(List<WeekDay> days) {}

    public record Holiday(
            UUID id,
            String date,
            String name,
            @JsonProperty("half_day") boolean halfDay,
            @JsonProperty("close_time") String closeTime) {}

    public record Holidays(List<Holiday> items) {}

    /** A new holiday; a half-day names the time it closes at (FR-CFG-021). */
    public record HolidayRequest(
            String date, String name, @JsonProperty("half_day") Boolean halfDay, @JsonProperty("close_time") String closeTime) {}

    /** Minutes before closing at which each channel stops issuing (FR-CFG-022); a channel left out has no cut-off. */
    public record Cutoffs(@JsonProperty("minutes_before_close") Map<String, Integer> minutesBeforeClose) {}

    /** The daily cap and its message, the duplicate policy and the roster rule of one Service (FR-CFG-023, FR-ISS-003, FR-ISS-004). */
    public record ServiceRule(
            @JsonProperty("daily_cap") Integer dailyCap,
            @JsonProperty("cap_message_i18n") Map<String, String> capMessageI18n,
            @JsonProperty("duplicate_policy") String duplicatePolicy,
            @JsonProperty("require_agent") Boolean requireAgent) {}

    /** Maintenance mode and the rate limits (FR-OPS-043, API-090). */
    public record Settings(
            @JsonProperty("maintenance_enabled") Boolean maintenanceEnabled,
            @JsonProperty("maintenance_message_i18n") Map<String, String> maintenanceMessageI18n,
            @JsonProperty("device_limit_per_minute") Integer deviceLimitPerMinute,
            @JsonProperty("visitor_limit_per_hour") Integer visitorLimitPerHour) {}

    /** A Service's remote-join policy (ticket 42, FR-MOB-010..011). {@code max_distance_m} null means the distance check is off. */
    public record RemoteRule(
            @JsonProperty("virtual_queue_enabled") Boolean virtualQueueEnabled,
            @JsonProperty("max_distance_m") Integer maxDistanceMeters,
            @JsonProperty("max_remote_share_pct") Integer maxRemoteSharePct,
            @JsonProperty("join_window_minutes") Integer joinWindowMinutes,
            @JsonProperty("arrival_deadline_minutes") Integer arrivalDeadlineMinutes) {}

    /** A Site's own coordinates (ticket 42, FR-MOB-011), for the max-distance leg of a remote join. */
    public record SiteLocation(Double latitude, Double longitude) {}
}
