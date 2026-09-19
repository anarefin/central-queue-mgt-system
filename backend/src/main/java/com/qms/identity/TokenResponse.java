package com.qms.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of login and refresh. The refresh token is never in the body; browsers get it only as an HttpOnly cookie. */
record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("password_expired") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean passwordExpired) {

    static TokenResponse from(Session session) {
        return new TokenResponse(
                session.access().value(), "Bearer", session.access().expiresIn().toSeconds(), session.passwordExpired() ? Boolean.TRUE : null);
    }
}
