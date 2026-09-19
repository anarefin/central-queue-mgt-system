package com.qms.platform.realtime;

import java.util.ArrayList;
import java.util.List;

/**
 * One connection's place on one topic. While {@code syncing} the connection has been told nothing yet, so the deltas that
 * arrive are held; {@link #live} sends the snapshot and then exactly the held deltas that come after it, so the snapshot
 * always precedes its deltas and nothing between them is lost. All methods run under the topic's lock.
 */
final class Subscription {

    private final Connection connection;
    private boolean syncing;
    private boolean detached;
    private final List<Envelope> held = new ArrayList<>();

    Subscription(Connection connection, boolean syncing) {
        this.connection = connection;
        this.syncing = syncing;
    }

    boolean detached() {
        return detached;
    }

    void detach() {
        detached = true;
        held.clear();
    }

    void deliver(Envelope event) {
        if (syncing) held.add(event);
        else connection.send(event.frame());
    }

    /** Sends {@code first} (the snapshot) and then every held event after {@code afterSeq}, and starts delivering directly. */
    void live(String first, long afterSeq) {
        connection.send(first);
        for (Envelope event : held) {
            if (event.seq() > afterSeq) connection.send(event.frame());
        }
        held.clear();
        syncing = false;
    }

    void send(String frame) {
        connection.send(frame);
    }
}
