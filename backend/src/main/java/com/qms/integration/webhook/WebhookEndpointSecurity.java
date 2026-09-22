package com.qms.integration.webhook;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Guards a webhook endpoint URL against server-side request forgery: an admin names a URL this server later POSTs
 * to, unauthenticated, on its own initiative, from a background job — the same SSRF shape
 * {@code notification.PushEndpointSecurity} (ticket 39) guards a visitor-supplied push endpoint against, trimmed to
 * this seam's own needs. Requires HTTPS and resolves the host, rejecting any address that is loopback, link-local
 * (also catching the 169.254.169.254 cloud metadata address), site-local (RFC 1918), an IPv6 unique-local address
 * (fc00::/7), a wildcard or multicast address, or an IPv4-mapped IPv6 literal denoting one of those. Checked both
 * when an endpoint is created or updated and again immediately before every send ({@link WebhookDeliveryWorker}),
 * since DNS can rebind between the two.
 */
final class WebhookEndpointSecurity {

    private WebhookEndpointSecurity() {}

    static final class UnsafeEndpointException extends RuntimeException {
        UnsafeEndpointException(String reason) {
            super(reason);
        }
    }

    /** {@code allowInsecureForTests} exists only for a test's own local fake endpoint; no environment variable wires
     * it, and it defaults to {@code false} ({@link WebhookProperties#allowInsecureEndpointsForTests()}). */
    static void requireSafe(String endpoint, boolean allowInsecureForTests) {
        if (allowInsecureForTests) return;
        URI uri = parse(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new UnsafeEndpointException("scheme");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new UnsafeEndpointException("host");
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new UnsafeEndpointException("unresolvable");
        }
        if (addresses.length == 0) throw new UnsafeEndpointException("unresolvable");
        for (InetAddress address : addresses) {
            if (isUnsafe(address)) throw new UnsafeEndpointException("private_address");
        }
    }

    private static boolean isUnsafe(InetAddress address) {
        InetAddress unwrapped = unwrapIpv4Mapped(address);
        return unwrapped.isLoopbackAddress()
                || unwrapped.isLinkLocalAddress()
                || unwrapped.isSiteLocalAddress()
                || unwrapped.isAnyLocalAddress()
                || unwrapped.isMulticastAddress()
                || isIpv6UniqueLocal(unwrapped);
    }

    /** fc00::/7: the IPv6 analogue of RFC 1918, not covered by {@link InetAddress#isSiteLocalAddress()}, which only
     * recognises the legacy fec0::/10 range. */
    private static boolean isIpv6UniqueLocal(InetAddress address) {
        if (!(address instanceof Inet6Address)) return false;
        byte[] raw = address.getAddress();
        return (raw[0] & 0xfe) == 0xfc;
    }

    /** An IPv4-mapped IPv6 literal (::ffff:a.b.c.d) would otherwise sail past every IPv4-specific check above.
     * Re-express it as the plain {@link Inet4Address} it denotes before checking. */
    private static InetAddress unwrapIpv4Mapped(InetAddress address) {
        if (!(address instanceof Inet6Address ip6)) return address;
        byte[] raw = ip6.getAddress();
        boolean isMapped = raw.length == 16
                && Arrays.equals(Arrays.copyOfRange(raw, 0, 10), new byte[10])
                && (raw[10] & 0xff) == 0xff
                && (raw[11] & 0xff) == 0xff;
        if (!isMapped) return address;
        try {
            return InetAddress.getByAddress(Arrays.copyOfRange(raw, 12, 16));
        } catch (UnknownHostException e) {
            return address;
        }
    }

    private static URI parse(String endpoint) {
        try {
            return new URI(endpoint);
        } catch (URISyntaxException | NullPointerException e) {
            throw new UnsafeEndpointException("malformed");
        }
    }
}
