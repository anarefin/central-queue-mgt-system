package com.qms.issuance.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Which Site to seed the active vertical profile's starter catalogue and numbering onto (ticket 67). */
public record SeedCatalogueRequest(@JsonProperty("site_id") UUID siteId) {}
