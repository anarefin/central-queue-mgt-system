package com.qms.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL for integration tests; no in-memory substitute (ADR-0012, NFR-POR-002). */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    public static final String IMAGE = "postgres:18-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE);
    }
}
