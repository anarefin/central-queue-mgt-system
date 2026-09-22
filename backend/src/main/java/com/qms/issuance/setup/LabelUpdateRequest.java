package com.qms.issuance.setup;

/** One label key's value for one language (CFG-003: any value a profile sets stays editable afterwards). */
public record LabelUpdateRequest(String lang, String value) {}
