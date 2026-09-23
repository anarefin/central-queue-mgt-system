package com.qms.platform.labels;

import java.util.Map;

/**
 * The one seam a bounded context reads a label override (terminology remapping, SRS §3.2) through: implemented once
 * by {@code com.qms.issuance.setup.LabelOverrideRepository}, the same separation {@link
 * com.qms.platform.featureflags.FeatureFlags} already gives a feature flag read, so a caller such as {@code
 * com.qms.device.DeviceService} (bootstrap, ticket 69) never depends on the {@code issuance.setup} package that
 * owns the {@code label_override} table and its admin screen.
 */
public interface Labels {

    /** Every currently-effective override for one language, key to value (e.g. {@code entity.visitor} -&gt; "Customer"). */
    Map<String, String> forLanguage(String lang);
}
