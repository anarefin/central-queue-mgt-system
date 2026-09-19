package com.qms.issuance;

import com.qms.issuance.TicketRepository.TicketRecord;
import com.qms.queue.QueueReads;
import org.springframework.stereotype.Component;

/** Turns a stored ticket into the response shape, adding its live place in the queue. */
@Component
class TicketViews {

    private final QueueReads queues;

    TicketViews(QueueReads queues) {
        this.queues = queues;
    }

    TicketResponse of(TicketRecord t) {
        ZoneRef zone = t.zoneId() == null ? null : new ZoneRef(t.zoneId(), t.zoneName(), t.buildingLabel(), t.floorLabel());
        return new TicketResponse(
                t.id(),
                t.tokenNumber(),
                t.state(),
                new NameRef(t.serviceId(), t.serviceNames()),
                new NameRef(t.groupId(), t.groupNames()),
                t.siteId(),
                zone,
                t.visitId(),
                t.originChannel(),
                queues.positionOf(t.id()),
                null, // the estimate arrives with wait estimation (FR-QUE-040..042)
                t.issuedAt(),
                t.queuedAt(),
                t.version(),
                null);
    }
}
