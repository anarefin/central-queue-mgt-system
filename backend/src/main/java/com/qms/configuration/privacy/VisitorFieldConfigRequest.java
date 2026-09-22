package com.qms.configuration.privacy;

/** {@code PUT /privacy/field-config/{surface}/{field}}'s body. */
public record VisitorFieldConfigRequest(Boolean visible) {}
