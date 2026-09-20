package com.qms.mobile;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of OTP verify and refresh. The refresh token is never in the body; it rides only the HttpOnly cookie (API-017). */
record VisitorTokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("token_type") String tokenType, @JsonProperty("expires_in") long expiresIn) {

    static VisitorTokenResponse from(VisitorSession session) {
        return new VisitorTokenResponse(session.access().value(), "Bearer", session.access().expiresIn().toSeconds());
    }
}
