package com.qms.configuration.approval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

record ApprovalRequest(@NotBlank String type, @NotNull Map<String, Object> payload) {}
