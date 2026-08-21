package com.concentus.support;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Set;

/**
 * Where this backend is willing to make a request to, on somebody else's say-so.
 *
 * <p>Any feature that takes an address from a user and fetches it turns the server into a proxy
 * for whatever the server can reach — which on a company network is a great deal more than the
 * person could reach themselves, and in a cloud is a metadata endpoint that hands out
 * credentials. A SQL source and a web page differ in every respect except this one, so the rule
 * lives in one place rather than being written twice and diverging.
 *
 * <p><b>Unresolvable fails closed.</b> A host that does not resolve is refused rather than tried:
 * the alternative is a name that resolves differently a second later, which is the shape of the
 * attack rather than an edge case.
 */
public final class OutboundHosts {

    /** Cloud-metadata addresses, blocked regardless of any allowlist (AWS/GCP/Azure and friends). */
    public static final Set<String> ALWAYS_BLOCKED = Set.of("169.254.169.254");

    private OutboundHosts() {
    }

    /** Whether a host is off limits by default: loopback, link-local, or cloud metadata. */
    public static boolean isBlocked(String host) {
        if (host == null || host.isBlank()) return true;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || ALWAYS_BLOCKED.contains(h)) return true;
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Refuses a host that is off limits, allowing the ones an operator has explicitly named.
     *
     * <p>The always-blocked set is checked <em>before</em> and independently of the allowlist:
     * otherwise naming the metadata address in configuration would silently defeat the guarantee
     * that it is always blocked.
     *
     * @param allowlist hosts an operator has opted into, lowercased; empty means "none"
     * @param setting   the configuration key to name in the refusal, so it can be acted on
     */
    public static void require(String host, Set<String> allowlist, String setting) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("A host is required.");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (ALWAYS_BLOCKED.contains(lower)) {
            throw new IllegalArgumentException(
                    "Host '" + host + "' is not allowed (cloud metadata hosts are always blocked).");
        }
        if (!allowlist.contains(lower) && isBlocked(host)) {
            throw new IllegalArgumentException(
                    "Host '" + host + "' is not allowed (localhost, link-local and cloud metadata "
                            + "hosts are blocked by default — see " + setting + ").");
        }
    }
}
