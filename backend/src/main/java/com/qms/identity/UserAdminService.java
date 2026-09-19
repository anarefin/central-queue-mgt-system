package com.qms.identity;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.Authz;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.Permission;
import com.qms.platform.security.Role;
import com.qms.platform.security.UserDisabled;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create, edit, disable and re-enable staff users and assign their roles (SRS §5.2). Permissions are enforced here,
 * at the service layer, so a second entry point cannot bypass them (API-016, FR-CFG-103). Every change writes an audit
 * entry with before and after values.
 */
@Service
@Profile(Profiles.SERVING)
public class UserAdminService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final UserRepository users;
    private final RoleAssignmentRepository assignments;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordService passwords;
    private final PasswordPolicy policy;
    private final AuditWriter audit;
    private final PrincipalChangedPublisher principalChanged;
    private final ApplicationEventPublisher events;
    private final CurrentUser currentUser;
    private final Authz authz;
    private final Clock clock;

    UserAdminService(
            UserRepository users,
            RoleAssignmentRepository assignments,
            RefreshTokenRepository refreshTokens,
            PasswordService passwords,
            PasswordPolicy policy,
            AuditWriter audit,
            PrincipalChangedPublisher principalChanged,
            ApplicationEventPublisher events,
            CurrentUser currentUser,
            Authz authz,
            Clock clock) {
        this.users = users;
        this.assignments = assignments;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.policy = policy;
        this.audit = audit;
        this.principalChanged = principalChanged;
        this.events = events;
        this.currentUser = currentUser;
        this.authz = authz;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @Transactional
    public UserView create(CreateUserCommand command) {
        AuthenticatedUser caller = currentUser.require();
        List<RoleAssignment> roles = command.roles() == null ? List.of() : command.roles();
        if (!roles.isEmpty()) {
            authz.require(Permission.ROLE_ASSIGN);
            AssignmentGuard.checkAssignable(caller, roles);
        }
        List<String> violations = policy.violations(command.password(), List.of(), passwords.encoder());
        if (!violations.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "password", "rules", violations));
        }

        UUID id;
        try {
            id = users.insert(command.username().trim(), passwords.hash(command.password()), command.displayName(), command.preferredLanguage(), clock.instant());
        } catch (DuplicateKeyException taken) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("field", "username"));
        }
        assignments.replaceAll(id, roles);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("username", command.username().trim());
        after.put("display_name", command.displayName());
        after.put("preferred_language", command.preferredLanguage());
        after.put("roles", rolesForAudit(roles));
        audit.record(AuditEvent.of("user.created", "user", id).withAfter(after));
        return view(id);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @Transactional
    public UserView update(UUID id, String displayName, String preferredLanguage) {
        UserAccount user = require(id);
        AssignmentGuard.checkTargetManageable(currentUser.require(), rolesOf(id));

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("display_name", user.displayName());
        before.put("preferred_language", user.preferredLanguage());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("display_name", displayName);
        after.put("preferred_language", preferredLanguage);

        users.updateProfile(id, displayName, preferredLanguage, clock.instant());
        audit.record(AuditEvent.of("user.updated", "user", id).withBefore(before).withAfter(after));
        return view(id);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).ROLE_ASSIGN)")
    @Transactional
    public UserView replaceRoles(UUID id, List<RoleAssignment> requested, String reason) {
        require(id);
        AuthenticatedUser caller = currentUser.require();
        List<RoleAssignment> before = assignments.findByUser(id);
        AssignmentGuard.checkTargetManageable(caller, rolesOf(before));
        AssignmentGuard.checkAssignable(caller, requested);

        assignments.replaceAll(id, requested);
        audit.record(AuditEvent.of("user.roles.changed", "user", id)
                .withBefore(Map.of("roles", rolesForAudit(before)))
                .withAfter(Map.of("roles", rolesForAudit(requested)))
                .withReason(reason));
        principalChanged.principalChanged(id); // sockets re-authorise against the new claims (ADR-0009)
        return view(id);
    }

    /**
     * Disables the account: refresh tokens are revoked so no new access token can be minted, the counter session is closed
     * and its tickets go back to waiting ({@link UserDisabled}, ADR-0008), and {@code principal.changed} is published so the
     * realtime hub drops the user's sockets. The current access token stays valid for REST until it expires (API-013,
     * FR-CFG-104).
     */
    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @Transactional
    public UserView disable(UUID id, String reason) {
        UserAccount user = require(id);
        AuthenticatedUser caller = currentUser.require();
        if (caller.userId().equals(id)) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "cannot_disable_self"));
        }
        AssignmentGuard.checkTargetManageable(caller, rolesOf(id));
        if (!user.active()) {
            return view(id);
        }
        users.setActive(id, false);
        int revoked = refreshTokens.revokeAllForUser(id, clock.instant());
        audit.record(AuditEvent.of("user.disabled", "user", id)
                .withBefore(Map.of("active", true))
                .withAfter(Map.of("active", false, "refresh_tokens_revoked", revoked))
                .withReason(reason));
        events.publishEvent(new UserDisabled(id, reason));
        principalChanged.principalChanged(id);
        return view(id);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @Transactional
    public UserView enable(UUID id) {
        UserAccount user = require(id);
        AssignmentGuard.checkTargetManageable(currentUser.require(), rolesOf(id));
        if (user.active()) {
            return view(id);
        }
        users.setActive(id, true);
        audit.record(AuditEvent.of("user.enabled", "user", id).withBefore(Map.of("active", false)).withAfter(Map.of("active", true)));
        return view(id);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        return view(id);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @Transactional(readOnly = true)
    public UserPage list(Integer limit, String cursor) {
        int effective = limit == null ? DEFAULT_LIMIT : limit;
        if (effective < 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "limit"));
        }
        effective = Math.min(effective, MAX_LIMIT);
        List<UserAccount> rows = new ArrayList<>(users.page(decode(cursor), effective + 1));
        String next = null;
        if (rows.size() > effective) {
            rows = new ArrayList<>(rows.subList(0, effective));
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(rows.getLast().username().toLowerCase().getBytes(StandardCharsets.UTF_8));
        }
        return new UserPage(rows.stream().map(this::toView).toList(), next);
    }

    // ---- internals ---------------------------------------------------------------------------------------------

    private UserAccount require(UUID id) {
        return users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private UserView view(UUID id) {
        return toView(require(id));
    }

    private UserView toView(UserAccount user) {
        List<UserView.RoleView> roles = assignments.findByUser(user.id()).stream()
                .map(a -> new UserView.RoleView(a.role().wire(), a.siteIds(), a.groupIds()))
                .toList();
        return new UserView(user.id(), user.username(), user.displayName(), user.preferredLanguage(), user.active(), roles, user.createdAt());
    }

    private Set<Role> rolesOf(UUID id) {
        return rolesOf(assignments.findByUser(id));
    }

    private static Set<Role> rolesOf(List<RoleAssignment> list) {
        return list.stream().map(RoleAssignment::role).collect(Collectors.toSet());
    }

    private static List<Map<String, Object>> rolesForAudit(List<RoleAssignment> list) {
        return list.stream()
                .map(a -> Map.<String, Object>of(
                        "role", a.role().wire(),
                        "site_ids", a.siteIds().stream().map(UUID::toString).sorted().toList(),
                        "group_ids", a.groupIds().stream().map(UUID::toString).sorted().toList()))
                .toList();
    }

    private static String decode(String cursor) {
        if (cursor == null) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "cursor"));
        }
    }
}
