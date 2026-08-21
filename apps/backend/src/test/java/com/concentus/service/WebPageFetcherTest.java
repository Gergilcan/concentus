package com.concentus.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the backend refuses to fetch on somebody else's say-so.
 *
 * <p>These are the tests that matter about this class. A server that fetches an address a user
 * typed is a proxy for everything the server can reach, which on a company network is a great deal
 * more than the person could reach themselves, and in a cloud is a metadata endpoint that hands
 * out credentials. The happy path is an HTTP call; the refusals are the contract.
 */
class WebPageFetcherTest {

    private final WebPageFetcher fetcher = new WebPageFetcher("");

    @Test
    void refusesTheCloudMetadataAddress() {
        assertThatThrownBy(() -> fetcher.fetch("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata");
    }

    @Test
    void refusesTheCloudMetadataAddressEvenWhenAllowlisted() {
        // The always-blocked set is checked before and independently of the allowlist: otherwise
        // naming it in configuration would quietly defeat the guarantee that it is always blocked.
        var permissive = new WebPageFetcher("169.254.169.254");

        assertThatThrownBy(() -> permissive.fetch("http://169.254.169.254/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("always blocked");
    }

    @Test
    void refusesLoopback() {
        assertThatThrownBy(() -> fetcher.fetch("http://localhost:8080/admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsLoopbackOnlyWhenAnOperatorAsksFor() {
        var permissive = new WebPageFetcher("localhost");

        // Not a successful fetch — nothing is listening — but it got past the guard, which is the
        // claim: an operator who names a host has opted into it.
        assertThatThrownBy(() -> permissive.fetch("http://localhost:59999/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not fetch");
    }

    @Test
    void refusesSchemesThatAreNotTheWeb() {
        // file: would read the server's disk; ftp: and gopher: are SSRF classics.
        for (String url : new String[] {"file:///etc/passwd", "ftp://example.com/x", "gopher://x/"}) {
            assertThatThrownBy(() -> fetcher.fetch(url))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http");
        }
    }

    @Test
    void refusesAnAddressWithNoHost() {
        assertThatThrownBy(() -> fetcher.fetch("https:///page"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAHostThatDoesNotResolve() {
        // Fails closed: a name that does not resolve now may resolve to something internal a
        // moment later, which is the shape of the attack rather than an edge case.
        assertThatThrownBy(() -> fetcher.fetch("http://no-such-host.invalid/page"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }
}
