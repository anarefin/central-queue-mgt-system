package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /devices/refresh}. Unlike staff refresh, the token travels in the body, not a cookie: a
 * kiosk/display shell is not a browser and holds its refresh credential in its own OS-permission-restricted file
 * (API-017).
 */
record DeviceRefreshRequest(@JsonProperty("refresh_token") String refreshToken) {}
