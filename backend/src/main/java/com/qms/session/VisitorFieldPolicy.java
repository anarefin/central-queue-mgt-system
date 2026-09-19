package com.qms.session;

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
            if (fields == null) return EnumSet.allOf(VisitorField.class);
            for (String field : fields) VisitorField.fromWire(field.strip()).ifPresent(visible::add);
        }
        return visible;
    }
}
