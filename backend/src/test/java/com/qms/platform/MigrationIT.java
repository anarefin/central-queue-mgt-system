package com.qms.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** FR-OPS-020 (forward-only, idempotent, separate step) and NFR-POR-002 (plain PostgreSQL, no extensions). */
@Testcontainers
class MigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration").load();
    }

    @Test
    void migrationsRunWithoutTheApplicationAndSecondRunAppliesNothing() {
        var first = flyway().migrate();
        var second = flyway().migrate();

        assertThat(first.migrationsExecuted).isPositive();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(flyway().validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void everyMigrationScriptCanBeReExecutedAgainstAnAlreadyMigratedDatabase() throws IOException, SQLException {
        flyway().migrate();

        List<Resource> scripts = new ArrayList<>(List.of(
                new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")));
        scripts.sort(Comparator.comparing(Resource::getFilename));
        assertThat(scripts).isNotEmpty();

        try (Connection connection = dataSource().getConnection()) {
            for (Resource script : scripts) {
                // One execute() per file: pgjdbc splits statements itself and understands $$-quoted function bodies.
                String sql = script.getContentAsString(StandardCharsets.UTF_8);
                try (var statement = connection.createStatement()) {
                    statement.execute(sql); // must not throw: idempotent
                }
            }
        }
    }

    @Test
    void onlyCorePostgresqlIsUsedNoExtensionsBeyondPlpgsql() {
        flyway().migrate();

        List<String> extensions = new JdbcTemplate(dataSource()).queryForList("select extname from pg_extension", String.class);

        assertThat(extensions).containsOnly("plpgsql");
    }

    @Test
    void baselineIsRecorded() {
        flyway().migrate();

        String value = new JdbcTemplate(dataSource())
                .queryForObject("select value from app_metadata where key = 'schema_baseline'", String.class);

        assertThat(value).isEqualTo("qms-phase1");
        assertThat(new ClassPathResource("db/migration/V1__baseline.sql").exists()).isTrue();
    }
}
