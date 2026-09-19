package com.qms.identity;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LoggingPrincipalChangedPublisher implements PrincipalChangedPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingPrincipalChangedPublisher.class);

    @Override
    public void principalChanged(UUID userId) {
        log.info("principal.changed userId={}", userId);
    }
}
