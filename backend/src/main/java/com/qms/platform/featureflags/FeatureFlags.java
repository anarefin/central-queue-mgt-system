package com.qms.platform.featureflags;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.Map;

/**
 * The one seam a bounded context asks whether a feature's org-wide master switch is on through (ticket 68, CFG-003,
 * SRS §27.5): implemented once by {@code com.qms.issuance.setup.FeatureFlagsService}, the same separation {@link
 * com.qms.platform.devices.DeviceConfigNotifier} already gives a device-config push, so a caller such as {@code
 * AppointmentBookingService} or {@code SiteController} never depends on the {@code issuance.setup} package that
 * owns the flag table and its admin screen.
 *
 * <p>A flag is an org-wide gate only: the feature it names works only when this is on <em>and</em> its own finer
 * setting (such as {@code JourneyService}'s {@code journeys_disabled} or the per-Service {@code
 * virtual_queue_enabled}) also allows it — this interface only answers the first half, and every gate checks it
 * first, before its own finer check.
 */
public interface FeatureFlags {

    boolean isEnabled(FeatureFlagKey key);

    /**
     * Refuses with {@link #refusal(FeatureFlagKey)} when {@code key}'s org-wide master switch is off; does nothing
     * when it is on.
     */
    default void require(FeatureFlagKey key) {
        if (!isEnabled(key)) throw refusal(key);
    }

    /** Every flag, by wire key, as {@link #isEnabled(FeatureFlagKey)} itself would answer for each — for {@code GET /config/bootstrap}. */
    Map<String, Boolean> all();

    /**
     * A {@code 409 conflict} with {@code details.reason = feature_disabled} and {@code details.feature = <key>}
     * (ticket 68): the one shared refusal every gate this ticket adds throws, whichever endpoint hits it. Exposed
     * separately from {@link #require} for a caller that folds the check into one of its own (a hidden field rather
     * than a whole endpoint, e.g. {@code announce_visitor_name}).
     */
    static ApiException refusal(FeatureFlagKey key) {
        return new ApiException(
                ErrorCode.CONFLICT, "feature.refused.feature_disabled", new Object[0], Map.of("reason", "feature_disabled", "feature", key.wire()));
    }
}
