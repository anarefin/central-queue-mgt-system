package com.qms.platform.security;

/** One cell of the §5.2 matrix: {@code Y}, {@code S} (own records only) or {@code —}. */
public enum Access {
    ALLOWED,
    /** Allowed only on the caller's own records; the object-level check is made in the service layer (FR-CFG-105). */
    OWN,
    DENIED
}
