package com.qms.mobile;

import java.util.List;

/** A bounded list response. A registered visitor's own tickets, appointments and saved sites are all few (NFR-CAP-001 precedent), so these are not paged. */
record Items<T>(List<T> items) {}
