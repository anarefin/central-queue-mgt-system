package com.qms.integration.serviceaccount;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.identity.AccessToken;
import com.qms.platform.Profiles;
import com.qms.platform.security.PublicEndpoint;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A host system's own sign-in (SRS §20.2's "Service account" row, ticket 58, FR-INT-030): a client id and secret in
 * the body, exchanged for the same shape of access token every other principal gets, carrying
 * {@link com.qms.platform.security.Role#HOST_SYSTEM} and the sites its service account was scoped to at creation.
 * Unlike staff sign-in there is no refresh token: the client secret is itself the long-lived credential (the
 * client-credentials shape §20.2 describes), so a host system simply authenticates again for its next token once
 * this one expires (at most 15 minutes later, API-013).
 */
@RestController
@RequestMapping("/auth/service-accounts")
@Profile(Profiles.SERVING)
public class ServiceAccountAuthController {

    record TokenRequest(@JsonProperty("client_id") String clientId, @JsonProperty("client_secret") String clientSecret) {}

    record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("token_type") String tokenType, @JsonProperty("expires_in") long expiresIn) {
        static TokenResponse from(AccessToken token) {
            return new TokenResponse(token.value(), "Bearer", token.expiresIn().toSeconds());
        }
    }

    private final ServiceAccountService service;

    ServiceAccountAuthController(ServiceAccountService service) {
        this.service = service;
    }

    @PublicEndpoint("A service account has no token yet; it presents its client id and secret instead (§20.2)")
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@RequestBody(required = false) TokenRequest request) {
        AccessToken issued = service.authenticate(request == null ? null : request.clientId(), request == null ? null : request.clientSecret());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(TokenResponse.from(issued));
    }
}
