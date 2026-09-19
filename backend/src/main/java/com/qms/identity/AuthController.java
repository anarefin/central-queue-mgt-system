package com.qms.identity;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.PublicEndpoint;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
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
 * Sign-in, silent refresh and sign-out for staff. The access token is returned in the body and held in memory by the
 * client; the refresh token travels only in an HttpOnly, Secure, SameSite=Strict cookie scoped to this path (API-017).
 */
@RestController
@RequestMapping("/auth")
@Profile(Profiles.SERVING)
public class AuthController {

    static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final SecurityProperties properties;

    AuthController(AuthService auth, UserRepository users, CurrentUser currentUser, SecurityProperties properties) {
        this.auth = auth;
        this.users = users;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @PublicEndpoint("Signing in cannot require being signed in")
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(auth.login(request.username(), request.password()));
    }

    @PublicEndpoint("Authenticated by the refresh-token cookie, not a bearer token")
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        return withRefreshCookie(auth.refresh(refreshCookie(request)));
    }

    @PublicEndpoint("Must work when the access token has already expired; only revokes the presented refresh token")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(refreshCookie(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public MeResponse me() {
        AuthenticatedUser principal = currentUser.require();
        UserAccount user = users.findById(principal.userId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        return new MeResponse(
                user.id(),
                user.username(),
                user.displayName(),
                user.preferredLanguage(),
                principal.roleNames().stream().toList(),
                principal.siteIds().stream().sorted().toList(),
                principal.groupIds().stream().sorted().toList());
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        auth.changePassword(currentUser.require().userId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), session.refreshMaxAge()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(TokenResponse.from(session));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(properties.refreshCookie().name(), value)
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
            if (cookie.getName().equals(properties.refreshCookie().name())) return cookie.getValue();
        }
        return null;
    }
}
