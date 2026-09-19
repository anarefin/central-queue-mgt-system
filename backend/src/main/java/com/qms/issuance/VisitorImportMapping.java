package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Which CSV header names FR-INT-011's column mapping reads for each target field of the {@code visitor} table.
 * {@code externalCodeColumn} and {@code nameColumn} are mandatory — an upsert needs a key, and every visitor has a
 * name; {@code phoneColumn}, {@code emailColumn} and {@code categoryColumn} are optional: {@code null} means that
 * CSV carries no such column, so the field is left untouched on an update and unset on an insert. The same mapping
 * serves a manual upload and every scheduled folder pickup, since a pickup has no admin present to map columns for it.
 */
public record VisitorImportMapping(
        @JsonProperty("external_code_column") String externalCodeColumn,
        @JsonProperty("name_column") String nameColumn,
        @JsonProperty("phone_column") String phoneColumn,
        @JsonProperty("email_column") String emailColumn,
        @JsonProperty("category_column") String categoryColumn) {

    /** Out of the box, a CSV whose header names already match the target fields needs no admin mapping at all. */
    static final VisitorImportMapping DEFAULT = new VisitorImportMapping("external_code", "name", "phone", "email", "category");
}
