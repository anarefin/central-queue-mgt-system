package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.dashboard.ThresholdAlertScheduler;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code site:{id}:alerts} over the realtime hub (ticket 47, §21.2, §21.4): a reconnecting subscriber is shown every
 * open alert as its snapshot, {@code alert.raised} delivers a fresh breach and {@code alert.acknowledged} its
 * acknowledgement. A real server and a real WebSocket, with the threshold sweep off so the test drives it itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig.class)
class ThresholdAlertRealtimeIT extends RealServerSupport {

    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
        registry.add("qms.alerts.threshold-check-cron", () -> "-");
        registry.add("qms.dashboard.refresh-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-alerts-realtime");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired ThresholdAlertScheduler scheduler;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aBreachDeliversAlertRaisedThenAcknowledgingItDeliversAlertAcknowledged() throws Exception {
        World w = world();
        Person admin = person(Role.ORG_ADMIN, w.site(), null);
        Socket console = connect(admin.token());

        console.subscribe("site:" + w.site() + ":alerts");
        Map<String, Object> snapshot = console.next("snapshot");
        assertThat(snapshot).containsEntry("topic", "site:" + w.site() + ":alerts");
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotData = (Map<String, Object>) snapshot.get("data");
        assertThat((java.util.List<?>) snapshotData.get("alerts")).as("nothing open yet").isEmpty();

        jdbc.update(
                "INSERT INTO service_alert_threshold (service_id, queue_length_max, updated_at) VALUES (?, 0, now())", w.service());
        issue(w.service());
        scheduler.tick();

        Map<String, Object> raised = event(console, "alert.raised");
        assertThat(raised).containsEntry("topic", "site:" + w.site() + ":alerts");
        @SuppressWarnings("unchecked")
        Map<String, Object> raisedData = (Map<String, Object>) raised.get("data");
        assertThat(raisedData).containsEntry("threshold_type", "queue_length");
        UUID alertId = UUID.fromString((String) raisedData.get("alert_id"));

        json(send("POST", "/alerts/" + alertId + "/acknowledge", admin.token(), "{\"note\":\"Looking into it\"}"));
        Map<String, Object> acked = event(console, "alert.acknowledged");
        @SuppressWarnings("unchecked")
        Map<String, Object> ackedData = (Map<String, Object>) acked.get("data");
        assertThat(ackedData).containsEntry("alert_id", alertId.toString()).containsEntry("note", "Looking into it");
    }

    @Test
    void aCallerOutsideTheirOwnSiteScopeIsDenied() throws Exception {
        World w = world();
        World other = world();
        Person elsewhere = person(Role.ORG_ADMIN, other.site(), null);
        Socket console = connect(elsewhere.token());

        console.subscribe("site:" + w.site() + ":alerts");
        Map<String, Object> denied = console.next("denied");
        assertThat(denied).containsEntry("topic", "site:" + w.site() + ":alerts").containsEntry("code", "forbidden");
    }

    private static Map<String, Object> event(Socket socket, String type) throws InterruptedException {
        return socket.next(f -> "event".equals(f.get("frame")) && type.equals(f.get("type")));
    }
}
