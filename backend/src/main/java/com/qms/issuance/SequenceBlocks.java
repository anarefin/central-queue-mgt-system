package com.qms.issuance;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Draws token sequence numbers from a site's reserved block (FR-QUE-201). A scope (here the token prefix, per site)
 * and a reset period each have their own run of blocks; the current block is locked while a number is taken, and the
 * next block is requested once 80% of the current one is consumed, so it is ready before the current one runs out
 * (the point at which a Phase 2 edge node would ask, and could not wait). The draw joins the caller's transaction, so
 * a ticket that fails to issue gives its number back and no gap is left behind.
 */
@Repository
class SequenceBlocks {

    /** Numbers reserved per block. */
    static final int BLOCK_SIZE = 100;
    /** The next block is requested when this share (percent) of the current one is consumed. */
    static final int REQUEST_AT_PERCENT = 80;

    private final JdbcTemplate jdbc;

    SequenceBlocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The scope a site's prefix is counted under; the same value keys the reset ledger. */
    static String scope(UUID siteId, String prefix) {
        return siteId + "/" + prefix;
    }

    long next(UUID siteId, String prefix, String resetKey) {
        return next(siteId, prefix, resetKey, 1);
    }

    /** The next number of the period; a period that has no block yet begins at {@code firstValue}. */
    long next(UUID siteId, String prefix, String resetKey, long firstValue) {
        String scope = scope(siteId, prefix);
        while (true) {
            List<Block> open = jdbc.query(
                    "SELECT id, block_start, block_end, next_value FROM sequence_block WHERE scope_key = ? AND reset_key = ? AND next_value <= block_end"
                            + " ORDER BY block_start LIMIT 1 FOR UPDATE",
                    (rs, i) -> new Block(rs.getObject("id", UUID.class), rs.getLong("block_start"), rs.getLong("block_end"), rs.getLong("next_value")),
                    scope, resetKey);
            if (!open.isEmpty()) {
                Block block = open.getFirst();
                jdbc.update("UPDATE sequence_block SET next_value = next_value + 1 WHERE id = ?", block.id());
                if ((block.nextValue() - block.start() + 1) * 100 >= (block.end() - block.start() + 1) * REQUEST_AT_PERCENT) requestNextBlock(siteId, scope, resetKey, block.end());
                return block.nextValue();
            }
            openBlock(siteId, scope, resetKey, firstValue);
        }
    }

    /** Opens the period's first block at {@code firstValue}; nothing happens when the period already has one. */
    void open(UUID siteId, String prefix, String resetKey, long firstValue) {
        String scope = scope(siteId, prefix);
        Integer blocks = jdbc.queryForObject("SELECT count(*) FROM sequence_block WHERE scope_key = ? AND reset_key = ?", Integer.class, scope, resetKey);
        if (blocks != null && blocks == 0) openBlock(siteId, scope, resetKey, firstValue);
    }

    /** The number the next draw would return, without taking it; empty when the period has no open block. */
    OptionalLong peek(UUID siteId, String prefix, String resetKey) {
        List<Long> next = jdbc.query(
                "SELECT next_value FROM sequence_block WHERE scope_key = ? AND reset_key = ? AND next_value <= block_end ORDER BY block_start LIMIT 1",
                (rs, i) -> rs.getLong(1),
                scope(siteId, prefix), resetKey);
        return next.isEmpty() ? OptionalLong.empty() : OptionalLong.of(next.getFirst());
    }

    // Concurrent callers that all found no open block insert the same start: one wins, the others wait for it to
    // commit, do nothing, and find its block on the next pass.
    private void openBlock(UUID siteId, String scope, String resetKey, long firstValue) {
        jdbc.update(
                "INSERT INTO sequence_block (id, scope_key, site_id, block_start, block_end, next_value, reset_key)"
                        + " SELECT ?, ?, ?, s.start_value, s.start_value + ? - 1, s.start_value, ?"
                        + " FROM (SELECT coalesce(max(block_end) + 1, ?) AS start_value FROM sequence_block WHERE scope_key = ? AND reset_key = ?) s"
                        + " ON CONFLICT (scope_key, reset_key, block_start) DO NOTHING",
                UUID.randomUUID(), scope, siteId, BLOCK_SIZE, resetKey, firstValue, scope, resetKey);
    }

    /** Opens the block after the one ending at {@code end}, unless it was already requested. The caller holds the current block's lock. */
    private void requestNextBlock(UUID siteId, String scope, String resetKey, long end) {
        jdbc.update(
                "INSERT INTO sequence_block (id, scope_key, site_id, block_start, block_end, next_value, reset_key)"
                        + " SELECT ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM sequence_block WHERE scope_key = ? AND reset_key = ? AND block_start > ?)"
                        + " ON CONFLICT (scope_key, reset_key, block_start) DO NOTHING",
                UUID.randomUUID(), scope, siteId, end + 1, end + BLOCK_SIZE, end + 1, resetKey, scope, resetKey, end);
    }

    private record Block(UUID id, long start, long end, long nextValue) {}
}
