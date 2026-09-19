package com.qms.identity;

import com.nimbusds.jose.jwk.ECKey;
import java.time.Instant;

/** A signing key pair. {@code retiredAt} is null for a key that can still sign. */
record SigningKey(String kid, ECKey jwk, Instant createdAt, Instant retiredAt) {

    boolean isActive() {
        return retiredAt == null;
    }
}
