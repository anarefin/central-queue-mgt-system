package com.qms.session;

import com.qms.platform.security.Access;
import com.qms.platform.security.Permission;
import com.qms.platform.security.PermissionMatrix;
import com.qms.platform.security.Role;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Which visitor fields the console may show a caller (FR-AGT-034). The set is decided on the server from the roles in the token, and
 * what is outside it is left out of the response, not merely hidden by the screen. A caller with several roles sees what any of their
 * roles is configured to see; a role with no configuration sees the full set.
 *
 * <p>The free-text note is additionally gated by an explicit permission on top of that config (FR-SEC-022, ticket
 * 54): even a role {@link ConsoleProperties} configures to include {@code purpose_note} only actually sees it while
 * at least one of the caller's roles carries {@code visitor_pii:view}, allowed or own (§5.2's own matrix gives every
 * staff role that can reach a counter session at least the "own" grant, so this is a real, always-enforced check
 * rather than dead code waiting on a role that does not exist yet — FR-CFG-103's "every permission check MUST be
 * enforced server-side" applies to this field exactly as it does to any other protected action).
 */
@Component
public class VisitorFieldPolicy {

    private final Map<String, List<String>> configured;

    VisitorFieldPolicy(ConsoleProperties properties) {
        this.configured = properties.visitorFields();
    }

    Set<VisitorField> visibleTo(Set<Role> roles) {
        return visibleTo(roles, configured);
    }

    static Set<VisitorField> visibleTo(Set<Role> roles, Map<String, List<String>> configured) {
        Set<VisitorField> visible = EnumSet.noneOf(VisitorField.class);
        if (roles.isEmpty()) return visible;
        for (Role role : roles) {
            List<String> fields = configured.get(role.wire());
            if (fields == null) {
                visible = EnumSet.allOf(VisitorField.class);
                break;
            }
            for (String field : fields) VisitorField.fromWire(field.strip()).ifPresent(visible::add);
        }
        return notesGated(roles, visible);
    }

    /** FR-SEC-022: strips {@link VisitorField#PURPOSE_NOTE} back out unless an explicit permission allows it. */
    static Set<VisitorField> notesGated(Set<Role> roles, Set<VisitorField> visible) {
        if (!visible.contains(VisitorField.PURPOSE_NOTE)) return visible;
        boolean permitted = roles.stream().anyMatch(role -> PermissionMatrix.access(role, Permission.VISITOR_PII_VIEW) != Access.DENIED);
        if (permitted) return visible;
        EnumSet<VisitorField> gated = EnumSet.copyOf(visible);
        gated.remove(VisitorField.PURPOSE_NOTE);
        return gated;
    }
}
