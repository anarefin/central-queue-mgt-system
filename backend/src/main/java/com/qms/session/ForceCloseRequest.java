package com.qms.session;

/** Why a stale session is being force-closed; it goes into the audit entry (FR-AGT-002). Optional. */
public record ForceCloseRequest(String reason) {}
