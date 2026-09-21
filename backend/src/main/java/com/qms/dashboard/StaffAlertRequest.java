package com.qms.dashboard;

/** Body of {@code POST /dashboard/{site_id}/staff-alert} (FR-MON-004): a supervisor's own free-text message, not one
 * of the trigger catalogue's own templated triggers (SRS §14.2 lists only the four automatic ones ticket 47 fires),
 * so it carries no template variables to validate. */
public record StaffAlertRequest(String message) {}
