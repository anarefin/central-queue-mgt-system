package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** API-011..015 and NFR-SEC-013: what the resource server accepts, and the exact shape of what it issues. */
class JwtValidationTest {

    @TempDir Path keyDir;

    MutableClock clock = MutableClock.now();
    SecurityProperties props;
    SigningKeyStore keys;
    AccessTokenService tokens;
    JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        props = SecurityProperties.forKeyDir(keyDir);
        keys = new SigningKeyStore(props, clock);
        tokens = new AccessTokenService(props, keys, clock);
        decoder = JwtDecoderFactory.create(props, keys);
    }

    private JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .jwtID(UUID.randomUUID().toString())
                .issuer(props.issuer())
                .audience(props.audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("roles", java.util.List.of("agent"));
    }

    private String signWithForeignEcKey(JWTClaimsSet claims, String kid) throws JOSEException {
        ECKey attacker = new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build(), claims);
        jwt.sign(new ECDSASigner(attacker));
        return jwt.serialize();
    }

    private String signWithActiveKey(JWTClaimsSet claims) throws JOSEException {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keys.activeKey().kid()).build(), claims);
        jwt.sign(new ECDSASigner(keys.activeKey().jwk()));
        return jwt.serialize();
    }

    // ---- what we issue (API-011, API-013) ----------------------------------------------------------------------

    @Test
    void issuedTokenCarriesExactlyTheDocumentedClaims() {
        UUID user = UUID.randomUUID();
        String value = tokens.issue(user, Set.of(Role.AGENT), Set.of(), Set.of()).value();

        Jwt jwt = decoder.decode(value);

        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("sub", "jti", "iss", "aud", "iat", "exp", "roles");
        assertThat(jwt.getSubject()).isEqualTo(user.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("agent");
        assertThat(jwt.getIssuer().toString()).isEqualTo(props.issuer());
        assertThat(jwt.getAudience()).containsExactly(props.audience());
        assertThat(jwt.getHeaders()).containsEntry("alg", "ES256");
    }

    @Test
    void scopedRolesAddSitesAndGroupsAndNothingElse() {
        UUID site = UUID.randomUUID();
        UUID group = UUID.randomUUID();

        Jwt jwt = decoder.decode(tokens.issue(UUID.randomUUID(), Set.of(Role.TEAM_ADMIN), Set.of(site), Set.of(group)).value());

        assertThat(jwt.getClaims().keySet())
                .containsExactlyInAnyOrder("sub", "jti", "iss", "aud", "iat", "exp", "roles", "sites", "groups");
        assertThat(jwt.getClaimAsStringList("sites")).containsExactly(site.toString());
        assertThat(jwt.getClaimAsStringList("groups")).containsExactly(group.toString());
    }

    @Test
    void noPermissionListIsInlined() {
        Jwt jwt = decoder.decode(tokens.issue(UUID.randomUUID(), Set.of(Role.SYSTEM_ADMIN), Set.of(), Set.of()).value());
        assertThat(jwt.getClaims().toString()).doesNotContain("perm:");
    }

    @Test
    void tokenLivesNoLongerThanFifteenMinutesAndEachHasItsOwnJti() {
        var first = tokens.issue(UUID.randomUUID(), Set.of(Role.AGENT), Set.of(), Set.of());
        var second = tokens.issue(UUID.randomUUID(), Set.of(Role.AGENT), Set.of(), Set.of());
        Jwt jwt = decoder.decode(first.value());

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isLessThanOrEqualTo(Duration.ofMinutes(15));
        assertThat(first.expiresIn()).isLessThanOrEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getId()).isNotEqualTo(decoder.decode(second.value()).getId());
    }

    @Test
    void configuringALongerLifetimeIsRefusedAtStartup() {
        assertThatThrownBy(() -> SecurityProperties.forKeyDir(keyDir).withAccessTokenTtl(Duration.ofMinutes(16)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("15");
    }

    // ---- what we reject (API-012) -------------------------------------------------------------------------------

    @Test
    void algNoneIsRejected() {
        String forged = new PlainJWT(validClaims().build()).serialize();

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void algNoneIsRejectedEvenWithTheRealKidInTheHeaderAndAnEmptySignature() throws Exception {
        var real = tokens.issue(UUID.randomUUID(), Set.of(Role.SYSTEM_ADMIN), Set.of(), Set.of()).value();
        String[] parts = real.split("\\.");
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());

        assertThatThrownBy(() -> decoder.decode(header + "." + parts[1] + "."))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void hmacSignedWithThePublicKeyAsSecretIsRejectedAlgorithmConfusion() throws Exception {
        byte[] publicKeyBytes = keys.activeKey().jwk().toPublicKey().getEncoded();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keys.activeKey().kid()).build(), validClaims().build());
        jwt.sign(new MACSigner(java.util.Arrays.copyOf(publicKeyBytes, 64)));

        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void rs256IsRejectedBecauseTheAlgorithmIsPinned() throws Exception {
        RSAKey rsa = new RSAKeyGenerator(2048).keyID(keys.activeKey().kid()).generate();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID()).build(), validClaims().build());
        jwt.sign(new RSASSASigner(rsa));

        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedByAnotherKeyIsRejectedWhetherOrNotItReusesOurKid() throws Exception {
        assertThatThrownBy(() -> decoder.decode(signWithForeignEcKey(validClaims().build(), "unknown-kid")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(signWithForeignEcKey(validClaims().build(), keys.activeKey().kid())))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void wrongIssuerWrongAudienceAndMissingAudienceAreRejected() throws Exception {
        assertThat(decoder.decode(signWithActiveKey(validClaims().build()))).isNotNull(); // control: the helper works

        assertThatThrownBy(() -> decoder.decode(signWithActiveKey(validClaims().issuer("https://evil.example").build())))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(signWithActiveKey(validClaims().audience("someone-else").build())))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(signWithActiveKey(validClaims().audience(java.util.List.of()).build())))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        String expired = signWithActiveKey(validClaims()
                .issueTime(Date.from(past.minus(Duration.ofMinutes(10))))
                .expirationTime(Date.from(past))
                .build());

        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenWithoutExpiryOrSubjectOrJtiIsRejected() throws Exception {
        assertThatThrownBy(() -> decoder.decode(signWithActiveKey(validClaims().expirationTime(null).build())))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(signWithActiveKey(validClaims().subject(null).build())))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(signWithActiveKey(validClaims().jwtID(null).build())))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        String token = tokens.issue(UUID.randomUUID(), Set.of(Role.AGENT), Set.of(), Set.of()).value();
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1])).replace("\"agent\"", "\"system_admin\"");
        String tampered = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes()) + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    // ---- keys: per installation, outside source control, rotatable (API-015, NFR-SEC-013) ------------------------

    @Test
    void keyPairIsGeneratedOnFirstRunAndReusedAfterARestart() {
        String kid = keys.activeKey().kid();

        var afterRestart = new SigningKeyStore(props, clock);

        assertThat(afterRestart.activeKey().kid()).isEqualTo(kid);
        assertThat(Files.exists(keyDir)).isTrue();
    }

    @Test
    void twoInstallationsGetDifferentKeys(@TempDir Path other) {
        var second = new SigningKeyStore(SecurityProperties.forKeyDir(other), clock);
        assertThat(second.activeKey().kid()).isNotEqualTo(keys.activeKey().kid());
    }

    @Test
    void privateKeyFilesAreOwnerOnly() throws Exception {
        try (var files = Files.list(keyDir)) {
            for (Path file : files.toList()) {
                if (Files.isDirectory(file)) continue;
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
                assertThat(perms).as(file.getFileName().toString()).isSubsetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
        }
    }

    @Test
    void rotationOverlapsOldTokensStayValidUntilTheWindowEnds() {
        String beforeRotation = tokens.issue(UUID.randomUUID(), Set.of(Role.AGENT), Set.of(), Set.of()).value();
        String oldKid = keys.activeKey().kid();

        keys.rotate();
        String afterRotation = tokens.issue(UUID.randomUUID(), Set.of(Role.AGENT), Set.of(), Set.of()).value();

        assertThat(keys.activeKey().kid()).isNotEqualTo(oldKid);
        assertThat(decoder.decode(beforeRotation)).isNotNull(); // old key still validates
        assertThat(decoder.decode(afterRotation)).isNotNull(); // new key validates
        assertThat(decoder.decode(afterRotation).getHeaders()).containsEntry("kid", keys.activeKey().kid());

        clock.advance(props.keyRotationOverlap().plusMinutes(1));

        assertThatThrownBy(() -> decoder.decode(beforeRotation)).isInstanceOf(JwtException.class);
        assertThat(decoder.decode(afterRotation)).isNotNull();
    }

    @Test
    void aSecondProcessSeesARotationWithoutRestarting() {
        var other = new SigningKeyStore(props, clock);
        String kidBefore = other.activeKey().kid();

        keys.rotate();
        clock.advance(props.keyRefreshInterval().plusSeconds(1)); // the other node re-reads the directory periodically

        assertThat(other.activeKey().kid()).isNotEqualTo(kidBefore);
        assertThat(other.activeKey().kid()).isEqualTo(keys.activeKey().kid());
    }
}
