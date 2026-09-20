package com.qms.configuration.branding;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Body of {@code PUT /print-template} (FR-CFG-031). */
public record PrintTemplateRequest(List<String> fields, @JsonProperty("notice_line") String noticeLine) {}
