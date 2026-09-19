package com.qms.issuance;

/** One CSV row FR-INT-011's validation report rejected. {@code line} is 1-based and counts the header row, so the first data row is line 2. */
public record VisitorImportError(int line, String field, String code) {}
