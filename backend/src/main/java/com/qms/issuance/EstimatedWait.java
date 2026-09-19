package com.qms.issuance;

/** A rounded range in minutes, never an exact promise (FR-QUE-042). Nothing computes it yet, so responses carry null. */
public record EstimatedWait(int low, int high) {}
