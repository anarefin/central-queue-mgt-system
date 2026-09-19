package com.qms.identity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Password hashing with bcrypt at cost 12 or more (NFR-SEC-001). */
@Service
class PasswordService {

    private static final int BCRYPT_MAX_BYTES = 72;

    private final PasswordEncoder encoder;
    private final String dummyHash;

    PasswordService(SecurityProperties properties) {
        this.encoder = new BCryptPasswordEncoder(properties.password().bcryptCost());
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        this.dummyHash = encoder.encode(HexFormat.of().formatHex(random));
    }

    String hash(String raw) {
        return encoder.encode(raw);
    }

    boolean matches(String raw, String hash) {
        if (raw.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            burn(raw);
            return false; // longer than bcrypt reads; no real password can be this long (the policy refuses them)
        }
        return encoder.matches(raw, hash);
    }

    /** Spends the same time as a real check, so an unknown or disabled username cannot be told apart by timing. */
    void burn(String raw) {
        encoder.matches(raw.length() > BCRYPT_MAX_BYTES ? raw.substring(0, BCRYPT_MAX_BYTES) : raw, dummyHash);
    }

    PasswordEncoder encoder() {
        return encoder;
    }
}
