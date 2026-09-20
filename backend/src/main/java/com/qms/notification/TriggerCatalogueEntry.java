package com.qms.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;

/** One row of the read-only trigger catalogue (SRS §14.2): what a template editor needs to know about a trigger. */
record TriggerCatalogueEntry(
        @JsonProperty("trigger_key") String triggerKey,
        @JsonProperty("default_channel_order") List<String> defaultChannelOrder,
        boolean essential,
        Set<String> variables) {

    static TriggerCatalogueEntry of(NotificationTriggerKey trigger) {
        return new TriggerCatalogueEntry(trigger.key(), trigger.defaultChannelOrder(), trigger.essential(), trigger.variables());
    }
}
