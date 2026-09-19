package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** A change of a waiting ticket's Priority class (FR-QUE-012). The {@code reason} is mandatory and goes to the audit log. */
public record ReprioritiseRequest(@JsonProperty("priority_class_id") UUID priorityClassId, String reason) {}
