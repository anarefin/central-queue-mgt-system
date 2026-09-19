package com.qms.identity;

import com.qms.platform.Profiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code java -jar app.jar --spring.profiles.active=rotate-keys} adds a new signing key and retires the previous one
 * (API-015). Tokens signed with the old key keep validating for {@code qms.security.key-rotation-overlap}. Running
 * nodes pick the new key up from the key directory within {@code qms.security.key-refresh-interval}.
 */
@Component
@Profile(Profiles.ROTATE_KEYS)
class KeyRotationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationRunner.class);

    private final SigningKeyStore keys;

    KeyRotationRunner(SigningKeyStore keys) {
        this.keys = keys;
    }

    @Override
    public void run(ApplicationArguments args) {
        keys.rotate();
        log.info("Signing key rotated, active key is now {}", keys.activeKey().kid());
    }
}
