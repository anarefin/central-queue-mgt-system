package com.qms.mobile;

import java.time.Duration;

/**
 * Sends a visitor their one-time code. The only seam the raw code is ever handed to outside {@link
 * VisitorAuthService} itself (API-018: the code must never be logged, and this interface's own implementations must
 * not log it either).
 */
interface VisitorOtpMailer {
    void sendCode(String email, String code, Duration validFor);
}
