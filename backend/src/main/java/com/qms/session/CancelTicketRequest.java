package com.qms.session;

/** A staff cancel of a ticket. The reason is optional and, when given, is kept in the audit log. */
public record CancelTicketRequest(String reason) {}
