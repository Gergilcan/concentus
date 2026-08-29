package com.concentus.git;

import com.concentus.integration.Redact;
import com.concentus.support.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lists the repositories in a GitHub organization or a GitLab group, so a repository node can be
 * filled in by picking rather than by pasting a URL.
 *
 * <p>Two providers, one shape. They differ in three ways that matter and in nothing else:
 * the header the token goes in ({@code Authorization: Bearer} vs {@code PRIVATE-TOKEN}), the field
 * holding the clone URL, and how pagination is expressed. Everything else is the same request.
 */
@Component
public class GitProviderClient {

    /** A page cap, so pointing at a large organization cannot hang the request forever. */
    private static final int MAX_PAGES = 20;
    private static final int PER_PAGE = 100;

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration timeout;

    public GitProviderClient(ObjectMapper mapper,
                             @Value("${git.api-timeout-seconds:30}") int timeoutSeconds) {
        this.mapper = mapper;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** One repository as the picker shows it. */
    public record RemoteRepo(String name, String fullName, String cloneUrl, String defaultBranch,
                             boolean archived, String description) {
    }

    public static class GitApiException extends RuntimeException {
        public GitApiException(String message) {
            super(message);
        }
    }

    /**
     * @param provider {@code github} or {@code gitlab}
     * @param group    an organization login (GitHub) or a group path such as {@code acme/backend}
     *                 (GitLab); GitLab subgroups are included
     * @param baseUrl  optional, for self-hosted GitLab or GitHub Enterprise
     */
    public List<RemoteRepo> listGroupRepos(String provider, String group, String token, String baseUrl) {
        if (group == null || group.isBlank()) throw new GitApiException("A group or organization is required.");
        String kind = provider == null ? "github" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "gitlab" -> listGitlab(group.trim(), token, defaulted(baseUrl, "https://gitlab.com"));
            case "github" -> listGithub(group.trim(), token, defaulted(baseUrl, "https://api.github.com"));
            default -> throw new GitApiException("Unknown provider '" + provider + "' (expected github | gitlab).");
        };
    }

    // ---- GitHub ----

    private List<RemoteRepo> listGithub(String org, String token, String base) {
        List<RemoteRepo> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            // The org endpoint 404s for a personal account, so fall back to the user's repos —
            // "my repositories" is a normal thing to want and should not read as a typo.
            String url = base + "/orgs/" + enc(org) + "/repos?per_page=" + PER_PAGE + "&page=" + page;
            JsonNode body;
            try {
                body = get(url, "Authorization", token == null ? null : "Bearer " + token);
            } catch (GitApiException e) {
                if (page == 1 && e.getMessage().contains("404")) {
                    return listGithubUser(org, token, base);
                }
                throw e;
            }
            if (!body.isArray() || body.isEmpty()) break;
            for (JsonNode n : body) out.add(githubRepo(n));
            if (body.size() < PER_PAGE) break;
        }
        return out;
    }

    private List<RemoteRepo> listGithubUser(String user, String token, String base) {
        List<RemoteRepo> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode body = get(base + "/users/" + enc(user) + "/repos?per_page=" + PER_PAGE + "&page=" + page,
                    "Authorization", token == null ? null : "Bearer " + token);
            if (!body.isArray() || body.isEmpty()) break;
            for (JsonNode n : body) out.add(githubRepo(n));
            if (body.size() < PER_PAGE) break;
        }
        return out;
    }

    private static RemoteRepo githubRepo(JsonNode n) {
        return new RemoteRepo(
                n.path("name").asText(null),
                n.path("full_name").asText(null),
                n.path("clone_url").asText(null),
                n.path("default_branch").asText("main"),
                n.path("archived").asBoolean(false),
                n.path("description").asText(null));
    }

    // ---- GitLab ----

    private List<RemoteRepo> listGitlab(String group, String token, String base) {
        List<RemoteRepo> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            // include_subgroups: a GitLab group is a tree, and "the group's repos" almost never
            // means only the ones sitting at the top level of it.
            String url = base + "/api/v4/groups/" + enc(group) + "/projects"
                    + "?include_subgroups=true&archived=false&per_page=" + PER_PAGE + "&page=" + page;
            JsonNode body = get(url, "PRIVATE-TOKEN", token);
            if (!body.isArray() || body.isEmpty()) break;
            for (JsonNode n : body) {
                out.add(new RemoteRepo(
                        n.path("path").asText(null),
                        n.path("path_with_namespace").asText(null),
                        n.path("http_url_to_repo").asText(null),
                        n.path("default_branch").asText("main"),
                        n.path("archived").asBoolean(false),
                        n.path("description").asText(null)));
            }
            if (body.size() < PER_PAGE) break;
        }
        return out;
    }

    // ---- HTTP ----

    private JsonNode get(String url, String header, String headerValue) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET();
            if (headerValue != null && !headerValue.isBlank()) b.header(header, headerValue);
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new GitApiException(explain(res.statusCode()) + " "
                        + Redact.secrets(res.body()));
            }
            return mapper.readTree(res.body());
        } catch (GitApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GitApiException("Could not reach the provider: " + e.getMessage());
        }
    }

    /** Maps a status to something an operator can act on rather than a bare number. */
    private static String explain(int status) {
        return switch (status) {
            case 401 -> "401 — the token was rejected. Check it has not expired.";
            case 403 -> "403 — the token lacks the scope to list this group's repositories "
                    + "(GitHub: `repo`/`read:org`; GitLab: `read_api`).";
            case 404 -> "404 — no such organization or group, or the token cannot see it.";
            default -> status + " —";
        };
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : Texts.trimTrailingSlashes(value);
    }

    /** GitLab group paths contain slashes and must arrive URL-encoded as a single path segment. */
    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
