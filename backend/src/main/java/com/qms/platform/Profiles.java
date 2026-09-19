package com.qms.platform;

/**
 * Spring profiles. {@code migrate} applies database migrations and exits; {@code rotate-keys} rotates the signing key
 * and exits. Neither serves requests, so components that need the web stack or the signing keys are marked
 * {@link #SERVING}.
 */
public final class Profiles {

    public static final String MIGRATE = "migrate";
    public static final String ROTATE_KEYS = "rotate-keys";
    public static final String SERVING = "!migrate & !rotate-keys";

    private Profiles() {}
}
