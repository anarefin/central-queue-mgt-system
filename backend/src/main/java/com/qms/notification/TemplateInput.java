package com.qms.notification;

/** A template's content, replaced as a whole (FR-NTF-020). {@code subject} is null for channels that have none. */
record TemplateInput(String subject, String body) {}
