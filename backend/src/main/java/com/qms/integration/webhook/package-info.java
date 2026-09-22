/**
 * Outbound webhooks (ticket 57, SRS §22.3, FR-INT-020..022): an admin subscribes an external endpoint to any §21.4
 * event type, delivery is HMAC-SHA256-signed and retried with backoff, and every delivery is logged and replayable.
 * Listens to {@link com.qms.platform.realtime.RealtimeEventOccurred}, the same decoupled seam
 * {@link com.qms.platform.realtime.RealtimePublisher} already raises for every realtime topic publish, so no other
 * bounded context changes to support this: a failing or slow endpoint can never affect queue operation.
 */
package com.qms.integration.webhook;
