package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Who owns {@code report-export:{user_id}} (ticket 49, FR-RPT-004): only the user it names, no database needed. */
class ReportExportTopicsTest {

    private final ReportExportTopics topics = new ReportExportTopics(new CurrentUser());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void handlesOnlyReportExportTopics() {
        assertThat(topics.handles(Topics.reportExport(UUID.randomUUID()))).isTrue();
        assertThat(topics.handles(Topics.staffAlert(UUID.randomUUID()))).isFalse();
    }

    @Test
    void theOwningUserMaySubscribe() {
        UUID userId = UUID.randomUUID();
        authenticateAs(userId);
        topics.authorize(Topics.reportExport(userId)); // does not throw
    }

    @Test
    void anotherUserIsRefused() {
        authenticateAs(UUID.randomUUID());
        assertThatThrownBy(() -> topics.authorize(Topics.reportExport(UUID.randomUUID())))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ApiException.class))
                .extracting(ApiException::code)
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void anUnauthenticatedSubscriberIsRefused() {
        assertThatThrownBy(() -> topics.authorize(Topics.reportExport(UUID.randomUUID())))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ApiException.class))
                .extracting(ApiException::code)
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void aMalformedTopicIsAValidationFailureNotAServerError() {
        authenticateAs(UUID.randomUUID());
        assertThatThrownBy(() -> topics.authorize(Topics.REPORT_EXPORT + "not-a-uuid"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ApiException.class))
                .extracting(ApiException::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static void authenticateAs(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(fakeJwt(userId), List.of()));
    }

    private static Jwt fakeJwt(UUID userId) {
        return Jwt.withTokenValue("fake")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("roles", List.of("org_admin"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
