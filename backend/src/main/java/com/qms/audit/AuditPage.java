package com.qms.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AuditPage(List<AuditEntry> items, @JsonProperty("next_cursor") String nextCursor) {}
