package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.support.PostgresContainerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FR-OPS-020: {@code --spring.profiles.active=migrate} applies migrations as a separate step and does not start the
 * serving parts, so it never generates signing keys or opens a web port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.flyway.enabled=true")
@ActiveProfiles("migrate")
@Import(PostgresContainerConfig.class)
class MigrateProfileIT {

    static final Path KEYS = Path.of(System.getProperty("java.io.tmpdir"), "qms-keys-should-not-exist-" + System.nanoTime());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEYS::toString);
    }

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationsAreAppliedWithoutStartingTheServingParts() {
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(3);

        assertThat(context.getBeanNamesForType(SigningKeyStore.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AuthController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(BootstrapAdmin.class)).isEmpty();
        assertThat(Files.exists(KEYS)).as("no signing key directory is created while only migrating").isFalse();
    }
}
