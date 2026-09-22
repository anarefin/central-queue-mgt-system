package com.qms.integration.webhook;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The closed set of event types a webhook endpoint may subscribe to (SRS §21.4, FR-INT-020): exactly what the
 * realtime hub itself may emit on a topic, transcribed independently here the same way {@code Permission} transcribes
 * §5.2 and {@code NotificationTriggerKey} transcribes §14.2. An event of any other type — an internal reaction such
 * as {@code ticket.forfeited} or {@code dashboard.staff_alert_sent} that is not in §21.4 — is never delivered, even
 * to an endpoint that (invalidly) named it, because {@link WebhookEventListener} only ever looks a type up here.
 */
public enum WebhookEventType {
    TICKET_ISSUED("ticket.issued"),
    TICKET_CALLED("ticket.called"),
    TICKET_REANNOUNCED("ticket.reannounced"),
    TICKET_MISSED("ticket.missed"),
    TICKET_SERVING("ticket.serving"),
    TICKET_HELD("ticket.held"),
    TICKET_COMPLETED("ticket.completed"),
    TICKET_NO_SHOW("ticket.no_show"),
    TICKET_CANCELLED("ticket.cancelled"),
    TICKET_TRANSFERRED("ticket.transferred"),
    TICKET_POSITION_CHANGED("ticket.position_changed"),
    QUEUE_ESTIMATE_CHANGED("queue.estimate_changed"),
    SESSION_OPENED("session.opened"),
    SESSION_BREAK_STARTED("session.break_started"),
    SESSION_BREAK_ENDED("session.break_ended"),
    SESSION_CLOSED("session.closed"),
    ALERT_RAISED("alert.raised"),
    ALERT_ACKNOWLEDGED("alert.acknowledged"),
    DEVICE_COMMAND("device.command"),
    CONFIG_CHANGED("config.changed");

    private final String wire;

    WebhookEventType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<WebhookEventType> fromWire(String wire) {
        return Arrays.stream(values()).filter(t -> t.wire.equals(wire)).findFirst();
    }

    public static boolean isKnown(String wire) {
        return fromWire(wire).isPresent();
    }

    /** Every wire string, in declaration order, for the catalogue an admin picks subscriptions from. */
    public static Set<String> wireValues() {
        Set<String> values = new LinkedHashSet<>();
        for (WebhookEventType type : values()) values.add(type.wire);
        return values;
    }
}
