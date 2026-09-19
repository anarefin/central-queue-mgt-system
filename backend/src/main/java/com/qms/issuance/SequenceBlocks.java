package com.qms.issuance;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Draws token sequence numbers from a site's reserved block (FR-QUE-201). A scope (here the token prefix, per site)
 * and a reset period each have their own run of blocks; the current block is locked while a number is taken, and when
 * it is used up the next block is opened. The draw joins the caller's transaction, so a ticket that fails to issue
 * gives its number back and no gap is left behind.
 */
@Repository
class SequenceBlocks {

    /** Numbers reserved per block. A Phase 2 edge node asks for a new block at 80% consumption. */
    static final int BLOCK_SIZE = 100;

    private final JdbcTemplate jdbc;

    SequenceBlocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long next(UUID siteId, String scopeKey, String resetKey) {
        String scope = siteId + "/" + scopeKey;
        while (true) {
            List<Block> open = jdbc.query(
                    "SELECT id, next_value FROM sequence_block WHERE scope_key = ? AND reset_key = ? AND next_value <= block_end"
                            + " ORDER BY block_start LIMIT 1 FOR UPDATE",
                    (rs, i) -> new Block(rs.getObject("id", UUID.class), rs.getLong("next_value")),
                    scope, resetKey);
            if (!open.isEmpty()) {
                Block block = open.getFirst();
                jdbc.update("UPDATE sequence_block SET next_value = next_value + 1 WHERE id = ?", block.id());
                return block.nextValue();
            }
            // Concurrent callers that all found no open block insert the same start: one wins, the others wait for it
            // to commit, do nothing, and find its block on the next pass.
            jdbc.update(
                    "INSERT INTO sequence_block (id, scope_key, site_id, block_start, block_end, next_value, reset_key)"
                            + " SELECT ?, ?, ?, s.start_value, s.start_value + ? - 1, s.start_value, ?"
                            + " FROM (SELECT coalesce(max(block_end), 0) + 1 AS start_value FROM sequence_block WHERE scope_key = ? AND reset_key = ?) s"
                            + " ON CONFLICT (scope_key, reset_key, block_start) DO NOTHING",
                    UUID.randomUUID(), scope, siteId, BLOCK_SIZE, resetKey, scope, resetKey);
        }
    }

    private record Block(UUID id, long nextValue) {}
}
