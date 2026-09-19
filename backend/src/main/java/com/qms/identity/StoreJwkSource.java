package com.qms.identity;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.List;

/** Feeds the decoder the public keys currently allowed to validate tokens; no network, no JWKS endpoint. */
final class StoreJwkSource implements JWKSource<SecurityContext> {

    private final SigningKeyStore store;

    StoreJwkSource(SigningKeyStore store) {
        this.store = store;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) {
        return selector.select(new JWKSet(List.<JWK>copyOf(store.verificationKeys())));
    }

    public void close() {
        // nothing to release
    }
}
