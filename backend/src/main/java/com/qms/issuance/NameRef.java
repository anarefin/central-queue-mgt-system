package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.UUID;

/** A service or service group as a ticket shows it: the id and its per-language names (FR-I18N-010). */
public record NameRef(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n) {}
