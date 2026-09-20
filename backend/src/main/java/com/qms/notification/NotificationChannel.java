package com.qms.notification;

import com.qms.notification.NotificationMessageRepository.MessageRow;

/**
 * An adapter that can attempt delivery of one already-rendered message (FR-INT-040, FR-NTF-005). Registering a bean
 * that implements this and naming its {@link #key()} in a trigger's channel order is the whole of adding a channel:
 * no change to the trigger catalogue, a template or the code that fires a trigger. In-app realtime and staff alert
 * (this ticket) are the first two adapters; Web Push (ticket 39) and email (ticket 40) add their own.
 */
interface NotificationChannel {

    /** The key a trigger's channel order names this adapter by, e.g. {@code in_app}. */
    String key();

    /** Attempts delivery once. Never throws: a failure is a {@link Outcome#failure(String) failed} outcome, not an exception. */
    Outcome send(MessageRow message);

    record Outcome(boolean success, String providerResponse) {
        static Outcome success(String providerResponse) {
            return new Outcome(true, providerResponse);
        }

        static Outcome failure(String reason) {
            return new Outcome(false, reason);
        }
    }
}
