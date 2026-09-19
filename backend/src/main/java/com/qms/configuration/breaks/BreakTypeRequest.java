package com.qms.configuration.breaks;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** The whole editable content of a break type, for create and for replace. An absent {@code max_minutes} means no limit. */
public record BreakTypeRequest(@JsonProperty("name_i18n") Map<String, String> nameI18n, @JsonProperty("max_minutes") Integer maxMinutes) {}
