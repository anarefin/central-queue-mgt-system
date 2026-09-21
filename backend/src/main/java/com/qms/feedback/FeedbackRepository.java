package com.qms.feedback;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code feedback} table (V39, ticket 45): one row per Ticket, its rating always readable, its comment gated by Team Admin approval. */
@Repository
class FeedbackRepository {

    /** What the {@code ticket} table alone can tell about a feedback candidate, read directly (no dependency on {@code com.qms.issuance}). */
    record TicketFacts(String state, UUID agentId) {}

    /** A feedback row as the submitter and the approver both need it. */
    record FeedbackRow(UUID id, UUID ticketId, int rating, String comment, boolean commentApproved, Instant submittedAt) {}

    /** One row of a Team Admin's review queue (comment awaiting a decision), with the token number for context. */
    record PendingComment(UUID id, UUID ticketId, String tokenNumber, int rating, String comment, Instant submittedAt) {}

    /** One row of an Agent's own feedback; {@code comment} is null until a Team Admin approves it (FR-MOB-033). */
    record MineRow(UUID ticketId, String tokenNumber, int rating, String comment, Instant submittedAt) {}

    private final JdbcTemplate jdbc;

    FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<TicketFacts> ticketFacts(UUID ticketId) {
        return jdbc.query(
                        "SELECT state, agent_id FROM ticket WHERE id = ?",
                        (rs, i) -> new TicketFacts(rs.getString("state"), (UUID) rs.getObject("agent_id")),
                        ticketId)
                .stream()
                .findFirst();
    }

    boolean existsForTicket(UUID ticketId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM feedback WHERE ticket_id = ?)", Boolean.class, ticketId);
        return Boolean.TRUE.equals(exists);
    }

    UUID insert(UUID ticketId, int rating, String comment, Instant submittedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback (id, ticket_id, rating, comment, submitted_at) VALUES (?, ?, ?, ?, ?)",
                id, ticketId, rating, comment, ts(submittedAt));
        return id;
    }

    Optional<FeedbackRow> find(UUID id) {
        return jdbc.query(
                        "SELECT id, ticket_id, rating, comment, comment_approved_at, submitted_at FROM feedback WHERE id = ?",
                        (rs, i) -> new FeedbackRow(
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("ticket_id"),
                                rs.getInt("rating"),
                                rs.getString("comment"),
                                rs.getObject("comment_approved_at") != null,
                                rs.getObject("submitted_at", OffsetDateTime.class).toInstant()),
                        id)
                .stream()
                .findFirst();
    }

    /** Approves the comment; idempotent (re-approving keeps the earliest submit but the latest approver and timestamp). */
    void approveComment(UUID id, UUID approverId, Instant now) {
        jdbc.update("UPDATE feedback SET comment_approved_by = ?, comment_approved_at = ? WHERE id = ?", approverId, ts(now), id);
    }

    /** A Team Admin's review queue: comments submitted but not yet approved, oldest first. */
    List<PendingComment> pendingComments() {
        return jdbc.query(
                "SELECT f.id, f.ticket_id, t.token_number, f.rating, f.comment, f.submitted_at"
                        + " FROM feedback f JOIN ticket t ON t.id = f.ticket_id"
                        + " WHERE f.comment IS NOT NULL AND f.comment_approved_at IS NULL"
                        + " ORDER BY f.submitted_at",
                (rs, i) -> new PendingComment(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("ticket_id"),
                        rs.getString("token_number"),
                        rs.getInt("rating"),
                        rs.getString("comment"),
                        rs.getObject("submitted_at", OffsetDateTime.class).toInstant()));
    }

    /**
     * An Agent's own feedback (FR-MOB-033): every rating on a Ticket bound to them, newest first; {@code comment} is
     * null unless a Team Admin has approved it, so an unapproved one is invisible here even though the row exists.
     */
    List<MineRow> mine(UUID agentId) {
        return jdbc.query(
                "SELECT t.id AS ticket_id, t.token_number, f.rating,"
                        + " CASE WHEN f.comment_approved_at IS NOT NULL THEN f.comment ELSE NULL END AS comment, f.submitted_at"
                        + " FROM feedback f JOIN ticket t ON t.id = f.ticket_id"
                        + " WHERE t.agent_id = ?"
                        + " ORDER BY f.submitted_at DESC",
                (rs, i) -> new MineRow(
                        (UUID) rs.getObject("ticket_id"),
                        rs.getString("token_number"),
                        rs.getInt("rating"),
                        rs.getString("comment"),
                        rs.getObject("submitted_at", OffsetDateTime.class).toInstant()),
                agentId);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
