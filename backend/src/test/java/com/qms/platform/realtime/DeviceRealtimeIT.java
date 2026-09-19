package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The {@code device:{id}} topic end to end (ticket 24, FR-OPS-042, FR-DSP-013): a device watches its own topic and
 * receives a pushed reload command, an administrator who can manage the device's site may watch it too, and revoking
 * the device drops its socket at once, the same as disabling a staff user (ADR-0009, §21.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig.class)
class DeviceRealtimeIT extends RealServerSupport {

    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-device-realtime");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Paired(UUID id, String accessToken) {}

    private Paired pairKiosk(String adminToken, UUID site) throws Exception {
        HttpResponse<String> code = send("POST", "/devices/pairing-codes", adminToken,
                "{\"kind\":\"kiosk\",\"site_id\":\"" + site + "\",\"label\":\"Front desk\"}");
        Map<String, Object> codeBody = json(code);
        HttpResponse<String> paired = send("POST", "/devices/pair", null, "{\"code\":\"" + codeBody.get("code") + "\"}");
        Map<String, Object> body = json(paired);
        return new Paired(UUID.fromString((String) body.get("device_id")), (String) body.get("access_token"));
    }

    @Test
    void aDeviceWatchesItsOwnTopicAndReceivesAPushedCommand() throws Exception {
        World w = world();
        Person admin = person(Role.ORG_ADMIN, w.site(), null);
        Paired device = pairKiosk(admin.token(), w.site());

        Socket socket = connect(device.accessToken());
        socket.subscribe("device:" + device.id());
        assertThat(socket.next("snapshot")).containsEntry("topic", "device:" + device.id());

        HttpResponse<String> pushed = send("POST", "/devices/" + device.id() + "/commands", admin.token(), "{\"command\":\"reload\"}");
        assertThat(pushed.statusCode()).as(pushed.body()).isEqualTo(204);

        Map<String, Object> event = socket.next("event");
        assertThat(event.get("type")).isEqualTo("device.command");
        assertThat(((Map<?, ?>) event.get("data")).get("command")).isEqualTo("reload");
    }

    @Test
    void anAdminWhoManagesTheSiteMayWatchTheDeviceButAnotherRoleMayNot() throws Exception {
        World w = world();
        Person admin = person(Role.ORG_ADMIN, w.site(), null);
        Person agent = person(Role.AGENT, w.site(), w.group());
        Paired device = pairKiosk(admin.token(), w.site());

        Socket adminSocket = connect(admin.token());
        adminSocket.subscribe("device:" + device.id());
        assertThat(adminSocket.next("snapshot")).containsEntry("topic", "device:" + device.id());

        Socket agentSocket = connect(agent.token());
        agentSocket.subscribe("device:" + device.id());
        assertThat(agentSocket.next("denied")).containsEntry("topic", "device:" + device.id()).containsEntry("code", "forbidden");
    }

    @Test
    void revokingTheDeviceDropsItsSocketAtOnce() throws Exception {
        World w = world();
        Person admin = person(Role.ORG_ADMIN, w.site(), null);
        Paired device = pairKiosk(admin.token(), w.site());
        Socket socket = connect(device.accessToken());
        socket.subscribe("device:" + device.id());
        socket.next("snapshot");

        HttpResponse<String> revoked = send("POST", "/devices/" + device.id() + "/revoke", admin.token(), null);

        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        assertThat(socket.awaitClose(5)).as("dropped at once, not at token expiry").isEqualTo(RealtimeHub.PRINCIPAL_CHANGED);
    }
}
