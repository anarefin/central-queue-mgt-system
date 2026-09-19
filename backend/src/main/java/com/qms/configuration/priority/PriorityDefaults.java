package com.qms.configuration.priority;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * The classes tickets get when staff choose none and nothing more specific applies (FR-QUE-011): one entry for each issuing
 * channel, {@code priority_class_id} null where none is set, and the Services that have a default of their own. A default
 * applies to tickets issued after it is set; tickets already issued keep the class they have (FR-CFG-041).
 */
public record PriorityDefaults(List<Channel> channels, List<Service> services) {

    public record Channel(String channel, @JsonProperty("priority_class_id") UUID priorityClassId) {}

    public record Service(@JsonProperty("service_id") UUID serviceId, @JsonProperty("priority_class_id") UUID priorityClassId) {}
}
