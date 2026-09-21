package com.qms.feedback;

import com.qms.platform.Profiles;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff side of post-service feedback (FR-MOB-033, ticket 45): a Team Admin's own review queue and decision on a
 * comment, and an Agent's own read of their feedback. Neither action has a row in the SRS §5.2 permission matrix (it
 * is not one of that table's fixed permissions), so both are authorised directly by role, the same
 * {@code hasRole(...)} shape a device or visitor role already is (see {@code Role}'s own doc comment) rather than a
 * new entry that would drift the matrix from SRS §5.2.
 */
@RestController
@RequestMapping("/feedback")
@Profile(Profiles.SERVING)
class FeedbackController {

    private final FeedbackService service;

    FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('TEAM_ADMIN')")
    @GetMapping("/pending-comments")
    public Items<Map<String, Object>> pendingComments() {
        return new Items<>(service.pendingComments());
    }

    @PreAuthorize("hasRole('TEAM_ADMIN')")
    @PostMapping("/{id}/approve-comment")
    public Map<String, Object> approveComment(@PathVariable UUID id) {
        return service.approveComment(id);
    }

    @PreAuthorize("hasRole('AGENT')")
    @GetMapping("/mine")
    public Items<Map<String, Object>> mine() {
        return new Items<>(service.mine());
    }
}
