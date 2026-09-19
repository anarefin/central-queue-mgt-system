package com.qms.identity;

import java.time.Duration;

/** A signed access token and how long it lives. */
public record AccessToken(String value, Duration expiresIn) {}
