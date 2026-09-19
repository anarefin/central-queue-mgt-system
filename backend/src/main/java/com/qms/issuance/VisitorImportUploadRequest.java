package com.qms.issuance;

/** The body of {@code POST /visitors/import} (FR-INT-011): a manual upload sends the CSV's raw text and its file name. */
public record VisitorImportUploadRequest(String filename, String content) {}
