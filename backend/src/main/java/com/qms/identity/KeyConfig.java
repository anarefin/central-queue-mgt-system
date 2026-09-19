package com.qms.identity;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The signing keys exist wherever the app is not just migrating: when serving and for the rotate-keys command. */
@Configuration
@Profile("!migrate")
class KeyConfig {

    @Bean
    SigningKeyStore signingKeyStore(SecurityProperties properties, Clock clock) {
        return new SigningKeyStore(properties, clock);
    }
}
