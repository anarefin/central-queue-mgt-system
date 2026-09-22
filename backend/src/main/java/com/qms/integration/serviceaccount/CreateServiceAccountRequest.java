package com.qms.integration.serviceaccount;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;
import java.util.UUID;

/** Body of {@code POST /service-accounts}: a label for the admin's own reference, and the sites this host system may
 * act on (FR-CFG-106) — never left empty (§20, no unrestricted machine credential). */
public record CreateServiceAccountRequest(String label, @JsonProperty("site_ids") Set<UUID> siteIds) {}
