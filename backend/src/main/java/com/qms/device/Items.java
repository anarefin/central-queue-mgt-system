package com.qms.device;

import java.util.List;

/** A bounded list response. Devices are few per installation (NFR-CAP-001 precedent), so this list is not paged. */
record Items<T>(List<T> items) {}
