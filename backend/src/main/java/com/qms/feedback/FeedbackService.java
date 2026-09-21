package com.qms.feedback;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.feedback.FeedbackRepository.FeedbackRow;
import com.qms.feedback.FeedbackRepository.MineRow;
import com.qms.feedback.FeedbackRepository.PendingComment;
import com.qms.feedback.FeedbackRepository.TicketFacts;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Post-service feedback (FR-MOB-033, ticket 45): a visitor's own optional rating and comment on their completed
 * Ticket; a Team Admin's review queue for the comment half; and an Agent's own read of their feedback, comment
 * included only once approved. Every write joins the caller's transaction with its audit entry, the same shape
 * {@code com.qms.issuance.VisitorTicketActions} already uses for the rest of the visitor ticket page.
 */
@Service
public class FeedbackService {

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;
    private static final int MAX_COMMENT_LENGTH = 2000;

    private final FeedbackRepository repository;
    private final AuditWriter audit;
    private final Clock clock;
    private final CurrentUser currentUser;

    FeedbackService(FeedbackRepository repository, AuditWriter audit, Clock clock, CurrentUser currentUser) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
        this.currentUser = currentUser;
    }

    /**
     * A visitor's own optional feedback on their own completed Ticket (FR-MOB-033): offered once, refused past that
     * or before completion. The caller (the visitor ticket page) has already proven ownership of {@code ticketId} by
     * its secret (§20.2); this never re-checks that, only that the Ticket exists and is ready for feedback.
     */
    @Transactional
    public Map<String, Object> submit(UUID ticketId, Integer rating, String comment) {
        if (rating == null) {
            throw validation("rating", "required");
        }
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw validation("rating", "out_of_range");
        }
        String trimmedComment = comment == null || comment.isBlank() ? null : comment.trim();
        if (trimmedComment != null && trimmedComment.length() > MAX_COMMENT_LENGTH) {
            throw validation("comment", "too_long");
        }

        TicketFacts facts = repository.ticketFacts(ticketId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!"completed".equals(facts.state())) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "ticket_not_completed"));
        }
        if (repository.existsForTicket(ticketId)) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "feedback_already_submitted"));
        }

        Instant now = clock.instant();
        repository.insert(ticketId, rating, trimmedComment, now);
        audit.record(AuditEvent.of("feedback.submitted", "ticket", ticketId)
                .withAfter(Map.of("rating", rating, "has_comment", trimmedComment != null))
                .withReason("visitor_ticket_page"));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ticket_id", ticketId.toString());
        view.put("rating", rating);
        view.put("comment", trimmedComment);
        view.put("submitted_at", now.toString());
        return view;
    }

    /** A Team Admin approves an individual comment for its Agent to see (FR-MOB-033); refused when there is none to approve. */
    @Transactional
    Map<String, Object> approveComment(UUID id) {
        FeedbackRow row = repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (row.comment() == null) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "no_comment"));
        }
        UUID approverId = currentUser.require().userId();
        Instant now = clock.instant();
        repository.approveComment(id, approverId, now);
        audit.record(AuditEvent.of("feedback.comment_approved", "feedback", id)
                .withAfter(Map.of("ticket_id", row.ticketId().toString()))
                .withReason("team_admin_approval"));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id.toString());
        view.put("ticket_id", row.ticketId().toString());
        view.put("comment_approved", true);
        return view;
    }

    /** A Team Admin's review queue (FR-MOB-033): every comment still waiting for a decision. */
    List<Map<String, Object>> pendingComments() {
        return repository.pendingComments().stream().map(FeedbackService::pendingView).toList();
    }

    /** The caller's own feedback (FR-MOB-033): every rating on a Ticket bound to them, comment included only once approved. */
    List<Map<String, Object>> mine() {
        UUID agentId = currentUser.require().userId();
        return repository.mine(agentId).stream().map(FeedbackService::mineView).toList();
    }

    private static Map<String, Object> pendingView(PendingComment row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id().toString());
        view.put("ticket_id", row.ticketId().toString());
        view.put("token_number", row.tokenNumber());
        view.put("rating", row.rating());
        view.put("comment", row.comment());
        view.put("submitted_at", row.submittedAt().toString());
        return view;
    }

    private static Map<String, Object> mineView(MineRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ticket_id", row.ticketId().toString());
        view.put("token_number", row.tokenNumber());
        view.put("rating", row.rating());
        view.put("comment", row.comment());
        view.put("submitted_at", row.submittedAt().toString());
        return view;
    }

    private static ApiException validation(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
