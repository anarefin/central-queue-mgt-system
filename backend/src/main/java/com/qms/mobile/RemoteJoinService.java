package com.qms.mobile;

import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssueCommand;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.TicketResponse;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * A registered visitor joining a Service's queue remotely, before arriving (ticket 42, SRS §13.2, FR-MOB-010): the
 * visitor is the caller and the subject both, exactly as a visitor's own appointment self-service already is
 * (ticket 41). The actual issuance — numbering, priority, every check {@code IssuanceGate} already makes for any
 * channel, plus this ticket's own virtual-queue, distance, share-cap and join-window checks — is
 * {@code IssuanceService#issueRemote}'s own job (public, in {@code com.qms.issuance}); this class only resolves the
 * caller and shapes the request, the same thin-adapter shape {@code TicketController} already is for reception.
 */
@Service
@Profile(Profiles.SERVING)
class RemoteJoinService {

    private final RemoteJoinRepository repository;
    private final IssuanceService issuance;
    private final CurrentUser currentUser;

    RemoteJoinService(RemoteJoinRepository repository, IssuanceService issuance, CurrentUser currentUser) {
        this.repository = repository;
        this.issuance = issuance;
        this.currentUser = currentUser;
    }

    /** What the join screen shows before the visitor commits (FR-MOB-023): the policy alone, not yet its enforcement. */
    RemoteJoinViews.PolicyView policy(UUID serviceId) {
        if (!repository.serviceExists(serviceId)) throw new ApiException(ErrorCode.NOT_FOUND);
        RemoteJoinRepository.Policy policy = repository.policyOf(serviceId);
        return new RemoteJoinViews.PolicyView(
                serviceId, policy.virtualQueueEnabled(), policy.maxDistanceMeters(), policy.maxRemoteSharePct(), policy.joinWindowMinutes(), policy.arrivalDeadlineMinutes());
    }

    TicketResponse join(UUID serviceId, Double latitude, Double longitude) {
        UUID visitorId = currentUser.require().userId();
        var command = new IssueCommand(serviceId, Channels.MOBILE, visitorId, ActorType.VISITOR, null, null, visitorId, false, null, null, null);
        return issuance.issueRemote(command, latitude, longitude);
    }
}
