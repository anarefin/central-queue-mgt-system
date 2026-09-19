package com.qms.session;

import com.qms.platform.Profiles;
import com.qms.platform.security.UserDisabled;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Hands a disabled user's counter session back to the queue as part of the disabling transaction (FR-CFG-104, ADR-0008). */
@Component
@Profile(Profiles.SERVING)
class SessionRevocation {

    private final SessionService sessions;

    SessionRevocation(SessionService sessions) {
        this.sessions = sessions;
    }

    @EventListener
    void on(UserDisabled disabled) {
        sessions.closeForDisabledUser(disabled.userId());
    }
}
