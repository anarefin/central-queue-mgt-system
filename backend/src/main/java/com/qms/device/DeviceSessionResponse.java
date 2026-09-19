package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Body of {@code POST /devices/pair} and {@code POST /devices/refresh}. Both tokens are in the body: the access
 * token is held in memory like any client (API-017), and the refresh token is the device's own to persist in its
 * OS-permission-restricted file, since a kiosk/display shell is not a browser and gets no cookie.
 */
record DeviceSessionResponse(
        @JsonProperty("device_id") UUID deviceId,
        String kind,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("zone_id") UUID zoneId,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("refresh_token") String refreshToken) {

    static DeviceSessionResponse from(DeviceSession session) {
        return new DeviceSessionResponse(
                session.device().id(),
                session.device().kind().wire(),
                session.device().siteId(),
                session.device().zoneId(),
                session.accessToken().value(),
                "Bearer",
                session.accessToken().expiresIn().toSeconds(),
                session.refreshToken());
    }
}
