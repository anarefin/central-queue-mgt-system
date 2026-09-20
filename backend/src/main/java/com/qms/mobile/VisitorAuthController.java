package com.qms.mobile;

import com.qms.identity.SecurityProperties;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.PublicEndpoint;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Email + OTP sign-in, silent refresh and sign-out for a registered visitor (ticket 41, FR-MOB-001, §20.2): the same
 * shape {@code com.qms.identity.AuthController} already is for staff, kept apart from it entirely (its own cookie
 * name and path, its own refresh-token table) so a browser signed in as both a staff member and a visitor — an
 * admin testing the mobile app, say — never confuses the two sessions.
 */
@RestController
@RequestMapping("/auth/visitor")
@Profile(Profiles.SERVING)
public class VisitorAuthController {

    static final String COOKIE_PATH = "/api/v1/auth/visitor";
    static final String COOKIE_NAME = "qms_visitor_refresh";

    record OtpRequestRequest(String email) {}

    record OtpVerifyRequest(String email, String code) {}

    record MeResponse(String id, String email) {}

    private final VisitorAuthService auth;
    private final VisitorAuthRepository repository;
    private final CurrentUser currentUser;
    private final SecurityProperties properties;

    VisitorAuthController(VisitorAuthService auth, VisitorAuthRepository repository, CurrentUser currentUser, SecurityProperties properties) {
        this.auth = auth;
        this.repository = repository;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @PublicEndpoint("Requesting a sign-in code cannot require being signed in")
    @PostMapping("/otp/request")
    public ResponseEntity<Void> requestOtp(@RequestBody(required = false) OtpRequestRequest request) {
        auth.requestOtp(request == null ? null : request.email());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PublicEndpoint("Verifying a sign-in code cannot require being signed in")
    @PostMapping("/otp/verify")
    public ResponseEntity<VisitorTokenResponse> verifyOtp(@RequestBody(required = false) OtpVerifyRequest request) {
        if (request == null) throw new ApiException(ErrorCode.VALIDATION_FAILED);
        return withRefreshCookie(auth.verifyOtp(request.email(), request.code()));
    }

    @PublicEndpoint("Authenticated by the refresh-token cookie, not a bearer token")
    @PostMapping("/refresh")
    public ResponseEntity<VisitorTokenResponse> refresh(HttpServletRequest request) {
        return withRefreshCookie(auth.refresh(refreshCookie(request)));
    }

    @PublicEndpoint("Must work when the access token has already expired; only revokes the presented refresh token")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(refreshCookie(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @PreAuthorize("hasRole('VISITOR')")
    @GetMapping("/me")
    public MeResponse me() {
        UUID visitorId = currentUser.require().userId();
        var visitor = repository.findById(visitorId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        return new MeResponse(visitor.id().toString(), visitor.email());
    }

    private ResponseEntity<VisitorTokenResponse> withRefreshCookie(VisitorSession session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), session.refreshMaxAge()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(VisitorTokenResponse.from(session));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.refreshCookie().secure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    private String refreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(COOKIE_NAME)) return cookie.getValue();
        }
        return null;
    }
}
