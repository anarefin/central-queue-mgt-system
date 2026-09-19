package com.qms.identity;

import com.qms.platform.realtime.RealtimePublisher;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Hands {@code principal.changed} to the realtime hub, which drops that subject's sockets (ADR-0009, FR-QUE-080). */
@Component
class HubPrincipalChangedPublisher implements PrincipalChangedPublisher {

    private final RealtimePublisher realtime;

    HubPrincipalChangedPublisher(RealtimePublisher realtime) {
        this.realtime = realtime;
    }

    @Override
    public void principalChanged(UUID userId) {
        realtime.principalChanged(userId.toString());
    }
}
