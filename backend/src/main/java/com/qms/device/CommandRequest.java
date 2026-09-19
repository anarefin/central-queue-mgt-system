package com.qms.device;

/** Body of {@code POST /devices/{id}/commands}: {@code reload} or {@code config_changed} (FR-OPS-042). */
record CommandRequest(String command) {}
