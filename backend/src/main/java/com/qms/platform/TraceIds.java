package com.qms.platform;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

/** Time-ordered trace ids (UUIDv7 layout) so log lines sort by arrival. */
public final class TraceIds {

    public static final String MDC_KEY = "trace_id";
    public static final String HEADER = "X-Trace-Id";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern ACCEPTABLE = Pattern.compile("^[0-9a-fA-F-]{8,64}$");

    private TraceIds() {}

    public static String next() {
        long millis = System.currentTimeMillis();
        long msb = (millis << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    /** Accepts an upstream id only if it is plain hex/dashes, so it can never inject into logs. */
    public static boolean isAcceptable(String candidate) {
        return candidate != null && ACCEPTABLE.matcher(candidate).matches();
    }
}
