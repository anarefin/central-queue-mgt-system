package com.qms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qms.support.PostgresContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The empty-but-real path: the whole application boots against real PostgreSQL and reports healthy. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class SkeletonIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void applicationIsReadyAndDatabaseIsUp() throws Exception {
        mvc.perform(get("/api/v1/health/ready")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/health/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependencies.database.status").value("up"));
    }

    @Test
    void flywayAppliedTheBaselineOnStart() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and version = '1'", Integer.class);
        assertThat(applied).isEqualTo(1);
    }
}
