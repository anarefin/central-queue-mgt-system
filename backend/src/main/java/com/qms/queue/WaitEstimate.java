package com.qms.queue;

/** A rounded range in minutes, never an exact promise (FR-QUE-042, FR-ISS-005): "about {@code low}-{@code high} minutes". */
public record WaitEstimate(int low, int high) {}
