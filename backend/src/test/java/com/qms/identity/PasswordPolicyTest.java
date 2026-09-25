package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** NFR-SEC-001: password length, complexity, expiry and reuse history are configurable. */
class PasswordPolicyTest {

    private static final BCryptPasswordEncoder FAST = new BCryptPasswordEncoder(4);

    private static PasswordPolicy policy(int minLength, int classes, int history, int maxAgeDays) {
        return new PasswordPolicy(new SecurityProperties.PasswordRules(minLength, classes, history, maxAgeDays, 12));
    }

    private final PasswordPolicy defaults = policy(12, 3, 5, 0);

    @Test
    void aStrongPasswordHasNoViolations() {
        assertThat(defaults.violations("Correct-Horse-9", List.of(), FAST)).isEmpty();
    }

    @Test
    void lengthBoundaryIsExact() {
        assertThat(defaults.violations("Abcdefgh1!x", List.of(), FAST)).contains("too_short"); // 11
        assertThat(defaults.violations("Abcdefgh1!xy", List.of(), FAST)).doesNotContain("too_short"); // 12
    }

    @Test
    void characterClassesAreCounted() {
        assertThat(defaults.violations("aaaaaaaaaaaa", List.of(), FAST)).contains("too_few_character_classes");
        assertThat(defaults.violations("aaaaaaaaaaaA", List.of(), FAST)).contains("too_few_character_classes"); // 2 of 3
        assertThat(defaults.violations("aaaaaaaaaaA1", List.of(), FAST)).doesNotContain("too_few_character_classes");
        assertThat(defaults.violations("aaaaaaaaaa!1", List.of(), FAST)).doesNotContain("too_few_character_classes");
    }

    @Test
    void passwordsBcryptWouldSilentlyTruncateAreRefused() {
        assertThat(defaults.violations("Aa1!" + "x".repeat(69), List.of(), FAST)).contains("too_long"); // 73 bytes
        assertThat(defaults.violations("Aa1!" + "x".repeat(68), List.of(), FAST)).doesNotContain("too_long"); // 72 bytes
        assertThat(defaults.violations("আ".repeat(25) + "Aa1", List.of(), FAST)).contains("too_long"); // multi-byte counts as bytes
    }

    @Test
    void recentPasswordsCannotBeReused() {
        String old = FAST.encode("Old-Password-1!");
        String older = FAST.encode("Older-Password-2!");

        assertThat(defaults.violations("Old-Password-1!", List.of(old, older), FAST)).contains("reused");
        assertThat(defaults.violations("Older-Password-2!", List.of(old, older), FAST)).contains("reused");
        assertThat(defaults.violations("Brand-New-Pass-3!", List.of(old, older), FAST)).doesNotContain("reused");
    }

    @Test
    void zeroHistoryDisablesTheReuseCheck() {
        String old = FAST.encode("Old-Password-1!");
        assertThat(policy(12, 3, 0, 0).violations("Old-Password-1!", List.of(old), FAST)).doesNotContain("reused");
    }

    @Test
    void rulesAreConfigurable() {
        var lenient = policy(8, 1, 0, 0);
        assertThat(lenient.violations("abcdefgh", List.of(), FAST)).isEmpty();
        assertThat(lenient.violations("abcdefg", List.of(), FAST)).containsExactly("too_short");
    }

    @Test
    void productionDefaultsAcceptAnyFivePlusCharacterPassword() {
        var production = policy(5, 1, 5, 0);
        assertThat(production.violations("abcde", List.of(), FAST)).isEmpty();
        assertThat(production.violations("12345", List.of(), FAST)).isEmpty();
        assertThat(production.violations("abcd", List.of(), FAST)).containsExactly("too_short");
    }

    @Test
    void expiryIsOffByDefaultAndOtherwiseCountsDays() {
        Instant changed = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(policy(12, 3, 5, 0).isExpired(changed, changed.plus(Duration.ofDays(3650)))).isFalse();
        var ninety = policy(12, 3, 5, 90);
        assertThat(ninety.isExpired(changed, changed.plus(Duration.ofDays(89)))).isFalse();
        assertThat(ninety.isExpired(changed, changed.plus(Duration.ofDays(91)))).isTrue();
    }
}
