package com.qms.platform.health;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dependencies that do not exist yet in the walking skeleton. Each later ticket that introduces the real component
 * (realtime hub: ticket 11, notification gateway: ticket 38) deletes its placeholder and registers a real probe.
 */
@Configuration
class PlaceholderProbes {

    @Bean
    DependencyProbe realtimeHubProbe() {
        return placeholder("realtime_hub");
    }

    @Bean
    DependencyProbe notificationGatewayProbe() {
        return placeholder("notification_gateway");
    }

    private static DependencyProbe placeholder(String name) {
        return new DependencyProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public State check() {
                return State.NOT_CONFIGURED;
            }
        };
    }
}
