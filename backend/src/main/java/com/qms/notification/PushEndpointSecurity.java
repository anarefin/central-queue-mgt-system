package com.qms.notification;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Guards a push endpoint against server-side request forgery (ticket 39): a visitor's own {@code push-subscription}
 * body names a URL this server later POSTs to, unauthenticated, on its own initiative, from a background job — the
 * exact shape of an SSRF vector if the URL is trusted unchecked (an internal service, or a cloud metadata endpoint).
 * Requires HTTPS (RFC 8292 assumes it regardless of this check) and resolves the host, rejecting any address that is
 * loopback, link-local (this also catches the 169.254.169.254 cloud metadata address), site-local (the RFC 1918
 * private ranges), an IPv6 unique-local address (fc00::/7 — {@link Inet6Address#isSiteLocalAddress()} does not cover
 * this range), a wildcard or multicast address. Called both when a visitor subscribes and again immediately before
 * every send ({@link WebPushChannel}), since DNS can rebind between the two.
 */
final class PushEndpointSecurity {

    private PushEndpointSecurity() {}

    static final class UnsafeEndpointException extends RuntimeException {
        UnsafeEndpointException(String reason) {
            super(reason);
        }
    }

    /** {@code allowInsecureForTests} ({@link WebPushProperties#allowInsecureEndpointsForTests()}) exists only for a
     * test's own local fake push service; no environment variable wires it, and it defaults to {@code false}. */
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

    /** fc00::/7: IPv6 unique-local addresses, the IPv6 analogue of RFC 1918 — not covered by
     * {@link InetAddress#isSiteLocalAddress()}, which only recognises the legacy fec0::/10 site-local range. */
    private static boolean isIpv6UniqueLocal(InetAddress address) {
        if (!(address instanceof Inet6Address)) return false;
        byte[] raw = address.getAddress();
        return (raw[0] & 0xfe) == 0xfc;
    }

    /** An IPv4-mapped IPv6 literal (::ffff:a.b.c.d) would otherwise sail past every IPv4-specific check above:
     * {@code Inet6Address.isLoopbackAddress()} etc. only recognise IPv6-native loopback/private forms. Re-express it
     * as the plain {@link Inet4Address} it denotes before checking. */
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
