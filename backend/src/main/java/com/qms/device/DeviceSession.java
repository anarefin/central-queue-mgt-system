package com.qms.device;

import com.qms.identity.AccessToken;

/** What pairing and refresh hand back: a fresh access token and the (rotated) refresh credential. */
record DeviceSession(Device device, AccessToken accessToken, String refreshToken) {}
