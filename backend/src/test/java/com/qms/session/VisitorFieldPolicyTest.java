package com.qms.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.security.Role;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** FR-AGT-034: which visitor fields the console may show a role, as configured (SRS §25.3). */
class VisitorFieldPolicyTest {

    private static ConsoleProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("qms.console", ConsoleProperties.class);
    }

    private static Set<VisitorField> seenBy(ConsoleProperties properties, Role... roles) {
        return VisitorFieldPolicy.visibleTo(Set.of(roles), properties.visitorFields());
    }

    @Test
    void aRoleWithNoConfigurationSeesTheFullSet() {
        assertThat(seenBy(bind(Map.of()), Role.AGENT)).containsExactlyInAnyOrder(VisitorField.values());
    }

    @Test
    void aConfiguredRoleSeesOnlyItsFieldsAndAnotherRoleKeepsTheDefault() {
        ConsoleProperties properties = bind(Map.of("qms.console.visitor-fields.agent", "code, category"));

        assertThat(seenBy(properties, Role.AGENT)).containsExactlyInAnyOrder(VisitorField.CODE, VisitorField.CATEGORY);
        assertThat(seenBy(properties, Role.TEAM_ADMIN)).containsExactlyInAnyOrder(VisitorField.values());
    }

    @Test
    void aRoleConfiguredWithNoFieldsSeesNoVisitorData() {
        assertThat(seenBy(bind(Map.of("qms.console.visitor-fields.agent", "")), Role.AGENT)).isEmpty();
    }

    @Test
    void aCallerWithSeveralRolesSeesWhatAnyOfThemMay() {
        ConsoleProperties properties = bind(Map.of("qms.console.visitor-fields.agent", "code", "qms.console.visitor-fields.team_admin", "name"));

        assertThat(seenBy(properties, Role.AGENT, Role.TEAM_ADMIN)).isEqualTo(EnumSet.of(VisitorField.CODE, VisitorField.NAME));
    }

    @Test
    void aCallerWithNoRoleSeesNothing() {
        assertThat(VisitorFieldPolicy.visibleTo(Set.of(), Map.of())).isEmpty();
    }

    @Test
    void aFieldOrRoleThatDoesNotExistIsRefusedAtStartUp() {
        assertThatThrownBy(() -> new ConsoleProperties(Map.of("agent", List.of("phone")))).hasMessageContaining("unknown visitor field 'phone'");
        assertThatThrownBy(() -> new ConsoleProperties(Map.of("janitor", List.of("code")))).hasMessageContaining("not a role");
    }
}
