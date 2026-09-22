package com.qms.integration.serviceaccount;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.identity.AccessToken;
import com.qms.identity.AccessTokenService;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.Role;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisioning a service account (admin-only, the same {@code user:manage} permission ticket 04 already established
 * for administering any other kind of principal's identity) and exchanging its client id and secret for a scoped
 * JWT (ticket 58, SRS §20.2, §22.4, FR-INT-030). An unknown client id, a wrong secret and an inactive account all
 * answer {@code invalid_credentials} after the same bcrypt work, exactly the shape {@code identity.LocalPasswordProvider}
 * already is for staff sign-in, so none can be told apart from outside.
 */
@Service
@Profile(Profiles.SERVING)
public class ServiceAccountService {

    static final String MANAGE = "hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)";
    private static final int BCRYPT_COST = 12;
    private static final int SECRET_BYTES = 32;
    private static final int CLIENT_ID_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServiceAccountRepository repository;
    private final AccessTokenService accessTokens;
    private final AuditWriter audit;
    private final Clock clock;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST);
    private final String dummyHash = encoder.encode(HexFormat.of().formatHex(randomBytes(16)));

    ServiceAccountService(ServiceAccountRepository repository, AccessTokenService accessTokens, AuditWriter audit, Clock clock) {
        this.repository = repository;
        this.accessTokens = accessTokens;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- provisioning (admin-facing) ------------------------------------------------------------------------------

    /** The secret is generated here and shown back exactly once, in this response, the same convention {@code
     * integration.webhook.WebhookEndpointService#create} already is for an endpoint secret. {@code siteIds} must
     * name at least one site: an empty scope elsewhere means "unrestricted" (FR-CFG-106), and a brand new machine
     * credential never starts there. */
    @PreAuthorize(MANAGE)
    @Transactional
    public ServiceAccountView create(String label, Set<UUID> siteIds, UUID actorId) {
        String trimmedLabel = requireLabel(label);
        Set<UUID> scoped = requireScope(siteIds);
        String clientSecret = randomToken(SECRET_BYTES);
        String clientId = "svc_" + randomToken(CLIENT_ID_BYTES);

        UUID id;
        try {
            id = repository.insert(clientId, encoder.encode(clientSecret), trimmedLabel, scoped, actorId, clock.instant());
        } catch (DuplicateKeyException collision) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("field", "client_id"));
        }
        audit.record(AuditEvent.of("service_account.created", "service_account", id).withAfter(snapshot(clientId, trimmedLabel, scoped, true)));
        return view(require(id)).withClientSecret(clientSecret);
    }

    @PreAuthorize(MANAGE)
    @Transactional(readOnly = true)
    public List<ServiceAccountView> list() {
        return repository.all().stream().map(ServiceAccountService::view).toList();
    }

    @PreAuthorize(MANAGE)
    @Transactional(readOnly = true)
    public ServiceAccountView get(UUID id) {
        return view(require(id));
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public ServiceAccountView deactivate(UUID id) {
        ServiceAccount current = require(id);
        if (current.active()) {
            repository.setActive(id, false, clock.instant());
            audit.record(AuditEvent.of("service_account.deactivated", "service_account", id)
                    .withBefore(Map.of("active", true)).withAfter(Map.of("active", false)));
        }
        return view(require(id));
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public ServiceAccountView activate(UUID id) {
        ServiceAccount current = require(id);
        if (!current.active()) {
            repository.setActive(id, true, clock.instant());
            audit.record(AuditEvent.of("service_account.activated", "service_account", id)
                    .withBefore(Map.of("active", false)).withAfter(Map.of("active", true)));
        }
        return view(require(id));
    }

    // ---- token exchange (host-system-facing, public: SRS §20.2's "client id and secret") --------------------------

    /** {@code hasRole('HOST_SYSTEM')} on the handful of endpoints this token then reaches is what actually limits
     * it (§20); this method itself needs no {@code @PreAuthorize} because it is how an otherwise unauthenticated
     * caller becomes one, the same shape {@code identity.AuthService#login} already is for staff. */
    @Transactional(noRollbackFor = ApiException.class)
    public AccessToken authenticate(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        ServiceAccount account = repository.findByClientId(clientId.trim()).orElse(null);
        if (account == null) {
            burn(clientSecret);
            audit.record(AuditEvent.of("service_account.auth_failed", "service_account", null)
                    .withAfter(Map.of("reason", "unknown_client_id")));
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!account.active()) {
            burn(clientSecret);
            audit.record(AuditEvent.of("service_account.auth_failed", "service_account", account.id()).withAfter(Map.of("reason", "inactive")));
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!encoder.matches(clientSecret, account.secretHash())) {
            audit.record(AuditEvent.of("service_account.auth_failed", "service_account", account.id()).withAfter(Map.of("reason", "bad_secret")));
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return accessTokens.issue(account.id(), Set.of(Role.HOST_SYSTEM), account.siteIds(), Set.of());
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private ServiceAccount require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** Spends the same bcrypt work a real check would, so an unknown client id or a disabled account cannot be told
     * apart from a wrong secret by timing (the same {@code PasswordService#burn} shape). */
    private void burn(String secret) {
        encoder.matches(secret, dummyHash);
    }

    private static String requireLabel(String label) {
        String trimmed = label == null ? null : label.strip();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "label", "code", "required"))));
        }
        if (trimmed.length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "label", "code", "Size"))));
        }
        return trimmed;
    }

    private static Set<UUID> requireScope(Set<UUID> siteIds) {
        Set<UUID> scoped = siteIds == null ? Set.of() : siteIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (scoped.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "site_ids", "code", "required"))));
        }
        return scoped;
    }

    private static String randomToken(int bytes) {
        return HexFormat.of().formatHex(randomBytes(bytes));
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static Map<String, Object> snapshot(String clientId, String label, Set<UUID> siteIds, boolean active) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("client_id", clientId);
        values.put("label", label);
        values.put("site_ids", siteIds.stream().map(UUID::toString).sorted().toList());
        values.put("active", active);
        return values;
    }

    private static ServiceAccountView view(ServiceAccount account) {
        return new ServiceAccountView(
                account.id(), account.clientId(), account.label(), account.siteIds(), account.active(), account.createdAt(), account.updatedAt(), null);
    }
}
