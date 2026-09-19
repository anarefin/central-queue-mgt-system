package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code POST /devices/{id}/heartbeat}: health and version (FR-OPS-041). Printer paper status is a Phase 2
 * concern (SRS §22.6): it has no field here yet. */
record HeartbeatRequest(@JsonProperty("app_version") String appVersion) {}
