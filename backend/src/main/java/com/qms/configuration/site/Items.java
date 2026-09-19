package com.qms.configuration.site;

import java.util.List;

/** A bounded list response. Sites, zones and counters are few (NFR-CAP-001), so these lists are not paged. */
public record Items<T>(List<T> items) {}
