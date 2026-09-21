/**
 * The supervisor's live dashboard (ticket 46, SRS §15.1, FR-MON-001..004): a read model over every other bounded
 * context's own tables (queue, session, device, appointment, configuration.site — the same "read another context's
 * table directly rather than depend on its package-private repository" shape {@code com.qms.queue.RemoteArrivalService}
 * already sets out, since nothing here writes any of them) plus the one write this ticket adds, a supervisor's manual
 * staff alert.
 */
package com.qms.dashboard;
