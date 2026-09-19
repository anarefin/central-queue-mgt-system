package com.qms.issuance;

import com.qms.issuance.TicketRepository.TicketRecord;
import com.qms.queue.QueueReads;
import com.qms.queue.WaitEstimates;
import org.springframework.stereotype.Component;

/** Turns a stored ticket into the response shape, adding its live place in the queue. */
@Component
class TicketViews {

    private final QueueReads queues;
    private final WaitEstimates estimates;

    TicketViews(QueueReads queues, WaitEstimates estimates) {
        this.queues = queues;
        this.estimates = estimates;
    }

    TicketResponse of(TicketRecord t) {
        ZoneRef zone = t.zoneId() == null ? null : new ZoneRef(t.zoneId(), t.zoneName(), t.buildingLabel(), t.floorLabel());
        Integer position = queues.positionOf(t.id());
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
                t.priorityClassId() == null ? null : new NameRef(t.priorityClassId(), t.priorityClassNames()),
                position,
                estimates.atPosition(t.serviceId(), position),
                t.issuedAt(),
                t.queuedAt(),
                t.version(),
                null);
    }
}
