package com.qms.session;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Checks for called tickets whose Agent has not acted within the call timeout (FR-QUE-032) every few seconds. Every node fires it;
 * {@link SessionService#promptTimedOutCalls()} claims each ticket in the database, so one node prompts each call and the rest find
 * nothing (ADR-0010). The schedule is a property so a test can switch it off with {@code qms.queue.call-timeout-check-cron=-} and
 * drive the check itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class CallTimeoutScheduler {

    private final SessionService sessions;

    CallTimeoutScheduler(SessionService sessions) {
        this.sessions = sessions;
    }

    @Scheduled(cron = "${qms.queue.call-timeout-check-cron:*/5 * * * * *}", zone = "UTC")
    void tick() {
        sessions.promptTimedOutCalls();
    }
}
