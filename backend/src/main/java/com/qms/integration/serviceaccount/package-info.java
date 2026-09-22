/**
 * Service accounts for host-system integration (ticket 58, SRS §20.2, §22.4, FR-INT-030): a client's own system (a
 * bank's app, a hospital portal, a BI tool) authenticates with a client id and secret, gets back a JWT scoped to the
 * sites its service account names and carrying {@link com.qms.platform.security.Role#HOST_SYSTEM}, and then drives
 * ticket issuance, appointment booking, and its own tickets' status and cancellation through the exact endpoints
 * every other principal uses (§20, no privileged internal path). An org admin provisions and revokes the credential
 * itself here; the token it is exchanged for is minted by {@code identity.AccessTokenService}, the same seam a
 * device's pairing already reuses.
 */
package com.qms.integration.serviceaccount;
