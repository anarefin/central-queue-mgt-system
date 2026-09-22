package com.qms.configuration.privacy;

import java.util.List;

/** A bounded list response; a surface's own field set is a handful of rows, never enough to need paging. */
record Items<T>(List<T> items) {}
