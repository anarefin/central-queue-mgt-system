package com.qms.platform.realtime;

import com.qms.platform.Profiles;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The polling fallback of SRS §21.5 (FR-QUE-084): where WebSocket is blocked a client asks for a topic's snapshot on an
 * interval. Whether the caller may see the topic is decided by the topic's own source, exactly as for a subscription.
 */
@RestController
@Profile(Profiles.SERVING)
public class StreamController {

    private final RealtimeHub hub;

    StreamController(RealtimeHub hub) {
        this.hub = hub;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/stream/snapshot")
    public Map<String, Object> snapshot(@RequestParam("topic") String topic) {
        return hub.snapshotOf(topic);
    }
}
