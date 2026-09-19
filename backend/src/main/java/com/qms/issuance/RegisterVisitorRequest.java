package com.qms.issuance;

/**
 * The body of {@code POST /visitors} (FR-ISS-021). {@code name} and {@code phone} are the minimum record; {@code
 * email}, {@code category} and {@code purpose} are optional and captured only when {@code
 * qms.visitor.registration-fields} turns them on (FR-SEC-023). {@code purpose} is not stored on the visitor — a
 * visitor is a reusable directory entry, but a purpose belongs to one visit — it is Reception's own cue, echoed back,
 * for the note ({@code purpose_note}) they add when they issue the ticket (FR-ISS-020).
 */
public record RegisterVisitorRequest(String name, String phone, String email, String category, String purpose) {}
