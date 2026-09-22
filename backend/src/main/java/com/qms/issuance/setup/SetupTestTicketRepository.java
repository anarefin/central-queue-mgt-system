package com.qms.issuance.setup;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The wizard's own test token (FR-OPS-010): a real Ticket issued through {@code IssuanceService}, tracked here so
 * go-live can be gated on it having been issued, printed, called and announced end to end. "Called" and "announced"
 * need no state of their own: a call always writes a {@code ticket_event} row carrying {@code announce: true}
 * (ticket 29, {@code session.SessionService}), so "announced" is read straight off that log, and "called" off the
 * ticket's own state (which a call moves past {@code waiting}, and stays past even once serving or completed).
 */
@Repository
class SetupTestTicketRepository {

    /** {@code state}/{@code tokenNumber} come straight from the ticket row the wizard's test token points to; {@code
     * announced} is whether a {@code to_state = 'called'} event has ever been recorded for it. */
    record LatestTestTicket(UUID ticketId, Instant issuedAt, Instant printedAt, String state, boolean announced, String tokenNumber) {}

    private final JdbcTemplate jdbc;

    SetupTestTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, UUID ticketId, Instant issuedAt, UUID issuedBy) {
        jdbc.update(
                "INSERT INTO setup_test_ticket (id, ticket_id, issued_at, issued_by) VALUES (?, ?, ?, ?)", id, ticketId, ts(issuedAt), issuedBy);
    }

    void confirmPrinted(UUID ticketId, Instant printedAt, UUID printedBy) {
        jdbc.update(
                "UPDATE setup_test_ticket SET printed_at = ?, printed_by = ?"
                        + " WHERE id = (SELECT id FROM setup_test_ticket WHERE ticket_id = ? ORDER BY issued_at DESC LIMIT 1)",
                ts(printedAt), printedBy, ticketId);
    }

    /** The most recently issued test token, joined to its ticket's live state, or empty when none has been issued yet. */
    Optional<LatestTestTicket> latest() {
        return jdbc.query(
                        "SELECT st.ticket_id, st.issued_at, st.printed_at, t.state, t.token_number,"
                                + " EXISTS (SELECT 1 FROM ticket_event e WHERE e.ticket_id = t.id AND e.to_state = 'called') AS announced"
                                + " FROM setup_test_ticket st JOIN ticket t ON t.id = st.ticket_id"
                                + " ORDER BY st.issued_at DESC LIMIT 1",
                        SetupTestTicketRepository::row)
                .stream()
                .findFirst();
    }

    Optional<LatestTestTicket> byTicketId(UUID ticketId) {
        return jdbc.query(
                        "SELECT st.ticket_id, st.issued_at, st.printed_at, t.state, t.token_number,"
                                + " EXISTS (SELECT 1 FROM ticket_event e WHERE e.ticket_id = t.id AND e.to_state = 'called') AS announced"
                                + " FROM setup_test_ticket st JOIN ticket t ON t.id = st.ticket_id"
                                + " WHERE st.ticket_id = ? ORDER BY st.issued_at DESC LIMIT 1",
                        SetupTestTicketRepository::row,
                        ticketId)
                .stream()
                .findFirst();
    }

    private static LatestTestTicket row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        OffsetDateTime printedAt = rs.getObject("printed_at", OffsetDateTime.class);
        return new LatestTestTicket(
                rs.getObject("ticket_id", UUID.class),
                rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                printedAt == null ? null : printedAt.toInstant(),
                rs.getString("state"),
                rs.getBoolean("announced"),
                rs.getString("token_number"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
