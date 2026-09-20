package com.qms.mobile;

import com.qms.identity.AccessToken;
import java.time.Duration;

/** What OTP verify and silent refresh hand back: an access token for the body and a refresh token for the cookie only. */
record VisitorSession(AccessToken access, String refreshToken, Duration refreshMaxAge) {}
