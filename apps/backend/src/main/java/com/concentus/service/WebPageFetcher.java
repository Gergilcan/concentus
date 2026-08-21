package com.concentus.service;

import com.concentus.support.OutboundHosts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fetching a page somebody wants in a knowledge base.
 *
 * <p>A manual, a policy or a runbook lives on an internal wiki as often as it lives in a PDF, and
 * asking somebody to print it to PDF first to get it in here is asking them to keep two copies —
 * the second of which starts going out of date immediately.
 *
 * <p><b>Only what the person asked for.</b> This fetches one address; it does not follow the links
 * on the page. A crawler that wanders is a crawler that ingests the whole intranet because
 * somebody pasted a nav page, and then answers questions from the cafeteria menu.
 *
 * <p><b>The address is checked before it is fetched</b>, through the same guard the SQL sources
 * use: a server fetching a URL on request is a proxy for everything the server can reach, which on
 * a company network is far more than the person could reach themselves. Redirects are followed
 * only to a host that passes the same check, because a redirect is a second address that nobody
 * typed.
 */
@Component
public class WebPageFetcher {

    /** Bytes of HTML worth reading. Past this it is an export, an archive, or a mistake. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private final Set<String> allowedHosts;

    public WebPageFetcher(@Value("${knowledge.allowed-web-hosts:}") String allowedHostsCsv) {
        this.allowedHosts = allowedHostsCsv == null || allowedHostsCsv.isBlank()
                ? Set.of()
                : Arrays.stream(allowedHostsCsv.split(","))
                        .map(String::trim)
                        .filter(h -> !h.isEmpty())
                        .map(h -> h.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    /** The page's bytes and the type the server said they were. */
    public record Page(byte[] bytes, String contentType) {
    }

    public Page fetch(String url) {
        URI uri = parse(url);
        OutboundHosts.require(uri.getHost(), allowedHosts, "knowledge.allowed-web-hosts");

        HttpClient client = HttpClient.newBuilder()
                // NEVER: a 302 to an internal address would walk straight past the check above.
                // Redirects are followed by hand below, each one re-checked.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        URI target = uri;
        for (int hop = 0; hop < 5; hop++) {
            HttpResponse<byte[]> response = send(client, target);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("location").orElse(null);
                if (location == null) {
                    throw new IllegalArgumentException("The server redirected without saying where.");
                }
                target = target.resolve(location);
                OutboundHosts.require(target.getHost(), allowedHosts, "knowledge.allowed-web-hosts");
                continue;
            }
            if (status != 200) {
                throw new IllegalArgumentException("The page answered HTTP " + status + ".");
            }
            byte[] body = response.body();
            if (body.length > MAX_BYTES) {
                throw new IllegalArgumentException("That page is larger than "
                        + (MAX_BYTES / 1024 / 1024) + " MB — too big to be a document.");
            }
            return new Page(body, response.headers().firstValue("content-type").orElse(""));
        }
        throw new IllegalArgumentException("The page redirected too many times.");
    }

    private HttpResponse<byte[]> send(HttpClient client, URI target) {
        try {
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(30))
                    // Named honestly. A fetcher pretending to be a browser is a fetcher whose
                    // traffic nobody can identify in a log when it misbehaves.
                    .header("User-Agent", "Concentus/1.0 (knowledge base ingest)")
                    .header("Accept", "text/html,text/plain,application/pdf;q=0.9,*/*;q=0.5")
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching " + target);
        } catch (Exception e) {
            String why = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            throw new IllegalArgumentException("Could not fetch that page: " + why);
        }
    }

    private static URI parse(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That is not a valid address.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http and https addresses can be fetched.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("That address has no host.");
        }
        return uri;
    }
}
