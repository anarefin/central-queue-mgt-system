package com.qms.issuance;

import java.util.List;

/** A bounded list response; visitor import runs are few enough not to need paging (§27.5 keeps this consistent with other admin lists). */
record Items<T>(List<T> items) {}
