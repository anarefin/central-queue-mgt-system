package com.qms.configuration.priority;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** The class a channel or a Service gives its tickets by default; null clears the default, so the ticket belongs to the normal class. */
public record PriorityDefaultRequest(@JsonProperty("priority_class_id") UUID priorityClassId) {}
