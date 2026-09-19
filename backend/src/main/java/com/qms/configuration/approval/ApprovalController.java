package com.qms.configuration.approval;

import com.qms.platform.Profiles;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Approval requests: a Team Admin asks, an Org Admin decides (FR-CFG-102). Permissions are also enforced in the service. */
@RestController
@RequestMapping("/approvals")
@Profile(Profiles.SERVING)
public class ApprovalController {

    private final ApprovalService service;

    ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @PreAuthorize("hasAnyAuthority(T(com.qms.platform.security.Authorities).TEAM_MEMBER_REQUEST, T(com.qms.platform.security.Authorities).COUNTER_ALLOCATION_REQUEST)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalView request(@Valid @RequestBody ApprovalRequest request) {
        return service.request(ApprovalType.fromWire(request.type()), request.payload());
    }

    @PreAuthorize("hasAnyAuthority(T(com.qms.platform.security.Authorities).TEAM_MEMBER_APPROVE, T(com.qms.platform.security.Authorities).COUNTER_ALLOCATION_APPROVE)")
    @GetMapping
    public List<ApprovalView> list(@RequestParam(required = false) String status) {
        return service.listForApprover(status);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/mine")
    public List<ApprovalView> mine() {
        return service.mine();
    }

    @PreAuthorize("hasAnyAuthority(T(com.qms.platform.security.Authorities).TEAM_MEMBER_APPROVE, T(com.qms.platform.security.Authorities).COUNTER_ALLOCATION_APPROVE)")
    @PostMapping("/{id}/approve")
    public ApprovalView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) DecisionRequest request) {
        return service.decide(id, true, request == null ? null : request.reason());
    }

    @PreAuthorize("hasAnyAuthority(T(com.qms.platform.security.Authorities).TEAM_MEMBER_APPROVE, T(com.qms.platform.security.Authorities).COUNTER_ALLOCATION_APPROVE)")
    @PostMapping("/{id}/reject")
    public ApprovalView reject(@PathVariable UUID id, @Valid @RequestBody(required = false) DecisionRequest request) {
        return service.decide(id, false, request == null ? null : request.reason());
    }
}
