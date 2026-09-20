package com.qms.mobile;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for {@link VisitorOtpMailer}: captures the code in memory instead of sending real email, so a test
 * can complete the OTP flow deterministically without a real (or fake) SMTP relay. */
public final class CapturingVisitorOtpMailer implements VisitorOtpMailer {

    private final Map<String, String> lastCode = new ConcurrentHashMap<>();
    private volatile int sendCount;

    @Override
    public void sendCode(String email, String code, Duration validFor) {
        lastCode.put(email.toLowerCase(Locale.ROOT), code);
        sendCount++;
    }

    public String lastCodeFor(String email) {
        return lastCode.get(email.toLowerCase(Locale.ROOT));
    }

    public int sendCount() {
        return sendCount;
    }
}
