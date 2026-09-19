package com.qms.platform.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qms.platform.WebSliceTestConfig;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** NFR-MNT-002: liveness, readiness and dependency health are three different answers. */
@WebMvcTest(HealthController.class)
@Import({WebSliceTestConfig.class, HealthControllerTest.Probes.class})
class HealthControllerTest {

    static final AtomicReference<DependencyProbe.State> DATABASE = new AtomicReference<>();

    @TestConfiguration
    static class Probes {
        @Bean
        DependencyProbe database() {
            return new DependencyProbe() {
                public String name() {
                    return "database";
                }

                public State check() {
                    return DATABASE.get();
                }
            };
        }

        @Bean
        DependencyProbe realtimeHubProbe() {
            return new PlaceholderProbes().realtimeHubProbe();
        }

        @Bean
        DependencyProbe notificationGatewayProbe() {
            return new PlaceholderProbes().notificationGatewayProbe();
        }
    }

    @Autowired MockMvc mvc;

    @BeforeEach
    void databaseIsUp() {
        DATABASE.set(DependencyProbe.State.UP);
    }

    @Test
    void livenessDoesNotDependOnTheDatabase() throws Exception {
        DATABASE.set(DependencyProbe.State.DOWN);
        mvc.perform(get("/api/v1/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("up"));
    }

    @Test
    void readyWhenDatabaseIsUp() throws Exception {
        mvc.perform(get("/api/v1/health/ready")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("up"));
    }

    @Test
    void notReadyWhenDatabaseIsDownUsesErrorEnvelope() throws Exception {
        DATABASE.set(DependencyProbe.State.DOWN);
        mvc.perform(get("/api/v1/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("unavailable"))
                .andExpect(jsonPath("$.error.details.dependency").value("database"));
    }

    @Test
    void dependencyHealthReportsPlaceholdersAsNotConfigured() throws Exception {
        mvc.perform(get("/api/v1/health/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("up"))
                .andExpect(jsonPath("$.dependencies.database.status").value("up"))
                .andExpect(jsonPath("$.dependencies.realtime_hub.status").value("not_configured"))
                .andExpect(jsonPath("$.dependencies.notification_gateway.status").value("not_configured"));
    }

    @Test
    void dependencyHealthIsAlwaysHttp200EvenWhenDown() throws Exception {
        DATABASE.set(DependencyProbe.State.DOWN);
        mvc.perform(get("/api/v1/health/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("down"))
                .andExpect(jsonPath("$.dependencies.database.status").value("down"));
    }
}
