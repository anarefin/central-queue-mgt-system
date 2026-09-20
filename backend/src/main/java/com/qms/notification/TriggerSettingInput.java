package com.qms.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A trigger's enabled state and, optionally, a channel order that replaces the catalogue's own default (FR-NTF-001, FR-NTF-010). */
record TriggerSettingInput(boolean enabled, @JsonProperty("channel_order") List<String> channelOrder) {}
