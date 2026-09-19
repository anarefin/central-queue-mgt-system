package com.qms.identity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The configurable password policy (NFR-SEC-001): length, character classes, reuse history and expiry. Violations are
 * returned as stable codes so a client can show them in the user's language.
 */
@Component
class PasswordPolicy {

    private static final int BCRYPT_MAX_BYTES = 72;

    private final SecurityProperties.PasswordRules rules;

    @Autowired
    PasswordPolicy(SecurityProperties properties) {
        this(properties.password());
    }

    PasswordPolicy(SecurityProperties.PasswordRules rules) {
        this.rules = rules;
    }

    List<String> violations(String candidate, List<String> previousHashes, PasswordEncoder encoder) {
        List<String> found = new ArrayList<>();
        if (candidate.codePointCount(0, candidate.length()) < rules.minLength()) {
            found.add("too_short");
        }
        if (candidate.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            found.add("too_long"); // bcrypt would silently ignore everything past 72 bytes
        }
        if (characterClasses(candidate) < rules.minCharacterClasses()) {
            found.add("too_few_character_classes");
        }
        if (rules.historyCount() > 0
                && previousHashes.stream().limit(rules.historyCount()).anyMatch(hash -> encoder.matches(candidate, hash))) {
            found.add("reused");
        }
        return found;
    }

    boolean isExpired(Instant passwordChangedAt, Instant now) {
        return rules.maxAgeDays() > 0 && passwordChangedAt.plus(Duration.ofDays(rules.maxAgeDays())).isBefore(now);
    }

    private static int characterClasses(String password) {
        boolean lower = false, upper = false, digit = false, other = false;
        for (int i = 0; i < password.length(); ) {
            int cp = password.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLowerCase(cp)) lower = true;
            else if (Character.isUpperCase(cp)) upper = true;
            else if (Character.isDigit(cp)) digit = true;
            else other = true;
        }
        return (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
    }
}
