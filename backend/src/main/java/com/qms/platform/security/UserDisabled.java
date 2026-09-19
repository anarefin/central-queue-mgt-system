package com.qms.platform.security;

import java.util.UUID;

/**
 * Published, inside the transaction that disables the account, so the contexts that hold work for a user can hand it back
 * (FR-CFG-104): the counter session closes and its tickets return to waiting. Lives in the platform so that identity need not
 * know who listens.
 *
 * @param userId the account that was disabled
 * @param reason why, as the administrator gave it; may be null
 */
public record UserDisabled(UUID userId, String reason) {}
