package com.qms.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller (or one of its methods) as deliberately reachable without a token. Every other controller method
 * must carry a {@code @PreAuthorize}; a build-time test enforces the split so the permission matrix cannot silently
 * drift from the code (FR-CFG-108). The security filter chain's permit-all list must agree with these markers.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PublicEndpoint {

    /** Why this endpoint is public, for the reviewer. */
    String value();
}
