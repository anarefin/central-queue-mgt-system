package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.dashboard.DashboardRefreshScheduler;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code site:{id}:dashboard} over the realtime hub (ticket 46, §21.2, FR-MON-001): who may subscribe is the same
 * reach as a Site's staff alerts, the first frame is a snapshot of the caller's own scope, and the refresh sweep
 * ({@link DashboardRefreshScheduler}) signals a live subscriber within its own cadence rather than leave the
 * dashboard to poll. A real server and a real WebSocket, with the sweep off so the test drives it itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig.class)
class DashboardRealtimeIT extends RealServerSupport {

    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
        registry.add("qms.dashboard.refresh-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-dashboard-realtime");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired DashboardRefreshScheduler scheduler;

    @Test
    void aSubscriberIsShownASnapshotOfTheirOwnScopeThenARefreshSignalFromTheSweep() throws Exception {
        World w = world();
        Person admin = person(Role.ORG_ADMIN, w.site(), null);
        Socket console = connect(admin.token());

        console.subscribe("site:" + w.site() + ":dashboard");
        Map<String, Object> snapshot = console.next("snapshot");
        assertThat(snapshot).containsEntry("topic", "site:" + w.site() + ":dashboard");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) snapshot.get("data");
        assertThat(data).containsEntry("site_id", w.site().toString()).containsKey("waiting_now").containsKey("device_health");

        scheduler.tick();
        Map<String, Object> refreshed = event(console, "dashboard.refreshed");
        assertThat(refreshed).containsEntry("topic", "site:" + w.site() + ":dashboard");
    }

    @Test
    void aCallerOutsideTheirOwnSiteScopeIsDenied() throws Exception {
        World w = world();
        World other = world();
        // Every dashboard-carrying permission of §5.2 still checks scope (FR-CFG-106): an Org Admin scoped to
        // another Site entirely is refused this one's dashboard, whatever permission tier they hold.
        Person elsewhere = person(Role.ORG_ADMIN, other.site(), null);
        Socket console = connect(elsewhere.token());

        console.subscribe("site:" + w.site() + ":dashboard");
        Map<String, Object> denied = console.next("denied");
        assertThat(denied).containsEntry("topic", "site:" + w.site() + ":dashboard").containsEntry("code", "forbidden");
    }

    private static Map<String, Object> event(Socket socket, String type) throws InterruptedException {
        return socket.next(f -> "event".equals(f.get("frame")) && type.equals(f.get("type")));
    }
}
