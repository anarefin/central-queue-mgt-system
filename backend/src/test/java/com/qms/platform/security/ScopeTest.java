package com.qms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FR-CFG-106: a scope id supplied by a client is intersected with the principal's claims, never trusted directly. */
class ScopeTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void scopedPrincipalOnlyKeepsRequestedIdsInsideItsClaim() {
        assertThat(Scope.intersect(Set.of(A, B), List.of(B, C))).containsExactly(B);
    }

    @Test
    void scopedPrincipalWithNoRequestedIdsGetsItsWholeClaim() {
        assertThat(Scope.intersect(Set.of(A, B), List.of())).containsExactlyInAnyOrder(A, B);
        assertThat(Scope.intersect(Set.of(A, B), null)).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void requestedIdsOutsideTheClaimYieldNothingNotEverything() {
        assertThat(Scope.intersect(Set.of(A), List.of(B, C))).isEmpty();
    }

    @Test
    void unrestrictedPrincipalKeepsWhatWasRequested() {
        assertThat(Scope.intersect(Set.of(), List.of(B, C))).containsExactlyInAnyOrder(B, C);
        assertThat(Scope.intersect(Set.of(), List.of())).isEmpty();
    }

    @Test
    void requireAcceptsInScopeAndRejectsOutOfScopeAsForbidden() {
        Scope.require(Set.of(A, B), B); // no exception
        assertThatThrownBy(() -> Scope.require(Set.of(A, B), C))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void requireOnUnrestrictedClaimAcceptsAnyId() {
        Scope.require(Set.of(), C);
    }

    @Test
    void requireRejectsAMissingIdRatherThanTreatingItAsAll() {
        assertThatThrownBy(() -> Scope.require(Set.of(A), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> Scope.require(Set.of(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
