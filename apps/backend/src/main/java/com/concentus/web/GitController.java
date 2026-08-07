package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.git.GitProviderClient;
import com.concentus.secrets.CredentialStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Browses a GitHub organization or a GitLab group, so a repository node can be filled in by
 * picking rather than by pasting a URL.
 *
 * <p>The credential is named by id and resolved here — the token itself never crosses the wire in
 * either direction, and a caller cannot supply one to have this endpoint use it.
 */
@RestController
@RequestMapping("/api/git")
public class GitController {

    private final GitProviderClient provider;
    private final CredentialStore credentials;
    private final OrgContext orgContext;

    public GitController(GitProviderClient provider, CredentialStore credentials, OrgContext orgContext) {
        this.provider = provider;
        this.credentials = credentials;
        this.orgContext = orgContext;
    }

    /**
     * @param provider     {@code github} or {@code gitlab}
     * @param group        organization login, or a GitLab group path (subgroups included)
     * @param credentialId a stored credential in the caller's own organization
     * @param baseUrl      optional, for self-hosted GitLab or GitHub Enterprise
     */
    @GetMapping("/repos")
    public Map<String, Object> repos(@RequestParam(name = "provider", defaultValue = "github") String providerId,
                                     @RequestParam String group,
                                     @RequestParam(required = false) String credentialId,
                                     @RequestParam(required = false) String baseUrl) {
        String token = credentialId == null || credentialId.isBlank() ? null
                : credentials.reveal(orgContext.requireOrganizationId(), credentialId).orElse(null);
        try {
            List<GitProviderClient.RemoteRepo> found =
                    provider.listGroupRepos(providerId, group, token, baseUrl);
            return Map.of("ok", true, "repos", found);
        } catch (RuntimeException e) {
            // Returned rather than thrown: the picker shows the reason inline, and a 500 here
            // would read as "Concentus is broken" when it is usually a token scope.
            return Map.of("ok", false, "error", e.getMessage(), "repos", List.of());
        }
    }
}
