package com.qms.identity;

import java.time.Duration;

/** What login and refresh hand back: an access token for the body and a refresh token for the cookie only. */
record Session(AccessToken access, String refreshToken, Duration refreshMaxAge, boolean passwordExpired) {}
