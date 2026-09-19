/**
 * Device pairing and fleet management (SRS §20.2, §20.4, §21.2; FR-OPS-011, FR-OPS-041, FR-OPS-042, FR-DSP-013,
 * NFR-SEC-005; ticket 24). A kiosk or display exchanges a short-lived pairing code, issued by an administrator, for
 * its own credential: an access token like any staff token (API-011) and a rotatable, hashed, individually revocable
 * refresh credential (API-014 precedent). A revoked device is dropped from the realtime hub at once via
 * {@code principal.changed} (§21.1, FR-DSP-013), and an administrator can push a reload or configuration update to a
 * live device over its {@code device:{id}} topic (FR-OPS-042).
 */
package com.qms.device;
