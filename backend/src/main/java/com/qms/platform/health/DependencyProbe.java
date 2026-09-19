package com.qms.platform.health;

/** One entry in the dependency-health report (NFR-MNT-002). */
public interface DependencyProbe {

    /** Stable wire name, e.g. {@code database}, {@code realtime_hub}, {@code notification_gateway}. */
    String name();

    State check();

    enum State {
        UP("up"),
        DOWN("down"),
        NOT_CONFIGURED("not_configured");

        private final String wire;

        State(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }
}
