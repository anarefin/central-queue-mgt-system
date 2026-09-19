package com.qms.platform.realtime;

import com.qms.platform.health.DependencyProbe;
import org.springframework.stereotype.Component;

/** The hub runs inside the backend (ADR-0010), so it is up for as long as it accepts connections (NFR-MNT-002). */
@Component
class RealtimeHubProbe implements DependencyProbe {

    private final RealtimeHub hub;

    RealtimeHubProbe(RealtimeHub hub) {
        this.hub = hub;
    }

    @Override
    public String name() {
        return "realtime_hub";
    }

    @Override
    public State check() {
        return hub.running() ? State.UP : State.DOWN;
    }
}
