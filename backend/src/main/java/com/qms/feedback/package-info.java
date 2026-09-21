/**
 * Post-service feedback (ticket 45, SRS §13.4 FR-MOB-033, §14.2, §18.3): a visitor's optional 1-5 rating and comment
 * on a completed Ticket, stored against the Ticket (and, through {@code ticket.agent_id}, the Agent who served it).
 * The rating feeds the aggregate Feedback report and is never gated; the comment is never shown to the Agent
 * individually until a Team Admin approves it. This package depends on nothing in another bounded context (it reads
 * the {@code ticket} table directly, the same "read the column, don't import the context" seam
 * {@code com.qms.queue.TicketEvents} already uses), so {@code com.qms.issuance}'s visitor-facing controller may
 * depend on it for the submit action with no risk of a package cycle (ArchitectureTest).
 */
package com.qms.feedback;
