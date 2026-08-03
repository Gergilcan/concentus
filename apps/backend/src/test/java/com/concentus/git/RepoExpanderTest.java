package com.concentus.git;

import com.concentus.config.AgentSpec.RepoSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning one group node into the repositories it stands for.
 *
 * <p>The cases worth pinning are the ones that would be invisible: a group silently truncated, a
 * branch inherited from the wrong place, or a listing failure that leaves the run with no
 * repositories and nothing said about it.
 */
class RepoExpanderTest {

    private StubClient client;
    private RepoExpander expander;

    /** Returns scripted repositories without touching the network. */
    private static class StubClient extends GitProviderClient {
        List<RemoteRepo> repos = new ArrayList<>();
        RuntimeException failure;
        String lastGroup;
        String lastBaseUrl;

        StubClient() {
            super(new com.fasterxml.jackson.databind.ObjectMapper(), 5);
        }

        @Override
        public List<RemoteRepo> listGroupRepos(String provider, String group, String token, String baseUrl) {
            lastGroup = group;
            lastBaseUrl = baseUrl;
            if (failure != null) throw failure;
            return repos;
        }
    }

    private static GitProviderClient.RemoteRepo repo(String fullName, String branch, boolean archived) {
        String name = fullName.substring(fullName.lastIndexOf('/') + 1);
        return new GitProviderClient.RemoteRepo(name, fullName,
                "https://github.com/" + fullName + ".git", branch, archived, "");
    }

    private static RepoSpec groupNode(String group) {
        RepoSpec spec = new RepoSpec();
        spec.provider = "github";
        spec.group = group;
        return spec;
    }

    @BeforeEach
    void setUp() {
        client = new StubClient();
        expander = new RepoExpander(client, 25);
    }

    @Test
    void aPlainUrlNodeIsLeftAlone() {
        RepoSpec spec = new RepoSpec();
        spec.url = "https://github.com/acme/api.git";

        List<RepoExpander.Expanded> out = expander.expand(List.of(spec));

        assertThat(out).hasSize(1);
        assertThat(out.getFirst().repos()).containsExactly(spec);
        // No listing call: a single repository needs no API access, so a node without a token works.
        assertThat(client.lastGroup).isNull();
    }

    @Test
    void anUnconfiguredNodeIsSkippedRatherThanFailing() {
        assertThat(expander.expand(List.of(new RepoSpec()))).isEmpty();
    }

    @Test
    void aGroupBecomesEveryRepositoryInIt() {
        client.repos = List.of(repo("acme/api", "main", false), repo("acme/web", "develop", false));

        List<RepoSpec> repos = expander.expandFlat(List.of(groupNode("acme")));

        assertThat(repos).extracting(r -> r.url)
                .containsExactly("https://github.com/acme/api.git", "https://github.com/acme/web.git");
    }

    @Test
    void eachRepositoryKeepsItsOwnDefaultBranch() {
        // A group's repositories rarely share a branch name; forcing "main" on all of them would
        // fail the clone of every repository that uses something else.
        client.repos = List.of(repo("acme/api", "main", false), repo("acme/web", "develop", false));

        List<RepoSpec> repos = expander.expandFlat(List.of(groupNode("acme")));

        assertThat(repos).extracting(r -> r.branch).containsExactly("main", "develop");
    }

    @Test
    void anExplicitBranchOnTheNodeWins() {
        client.repos = List.of(repo("acme/api", "main", false), repo("acme/web", "develop", false));
        RepoSpec node = groupNode("acme");
        node.branch = "release";

        assertThat(expander.expandFlat(List.of(node))).allSatisfy(r ->
                assertThat(r.branch).isEqualTo("release"));
    }

    @Test
    void archivedRepositoriesAreExcludedByDefault() {
        // They are read-only, so a flow that exists to open PRs can only fail on them.
        client.repos = List.of(repo("acme/api", "main", false), repo("acme/old", "main", true));

        assertThat(expander.expandFlat(List.of(groupNode("acme"))))
                .extracting(r -> r.url).containsExactly("https://github.com/acme/api.git");
    }

    @Test
    void archivedRepositoriesCanBeAskedFor() {
        client.repos = List.of(repo("acme/api", "main", false), repo("acme/old", "main", true));
        RepoSpec node = groupNode("acme");
        node.includeArchived = true;

        assertThat(expander.expandFlat(List.of(node))).hasSize(2);
    }

    @Test
    void aSelectionNarrowsTheGroupWithoutLeavingIt() {
        client.repos = List.of(repo("acme/api", "main", false), repo("acme/web", "main", false),
                repo("acme/docs", "main", false));
        RepoSpec node = groupNode("acme");
        node.only = List.of("acme/api", "acme/docs");

        assertThat(expander.expandFlat(List.of(node)))
                .extracting(r -> r.url)
                .containsExactly("https://github.com/acme/api.git", "https://github.com/acme/docs.git");
    }

    @Test
    void aSelectionAcceptsBareNamesToo() {
        // The list shows both forms; either is an unambiguous way to mean the same repository.
        client.repos = List.of(repo("acme/api", "main", false), repo("acme/web", "main", false));
        RepoSpec node = groupNode("acme");
        node.only = List.of("API");

        assertThat(expander.expandFlat(List.of(node))).hasSize(1);
    }

    @Test
    void theNodesCredentialAndServerCarryToEveryRepository() {
        client.repos = List.of(repo("acme/api", "main", false), repo("acme/web", "main", false));
        RepoSpec node = groupNode("acme");
        node.credentialId = "cred-1";
        node.baseUrl = "https://gitlab.empresa.com";

        List<RepoSpec> repos = expander.expandFlat(List.of(node));

        assertThat(repos).allSatisfy(r -> {
            assertThat(r.credentialId).isEqualTo("cred-1");
            assertThat(r.baseUrl).isEqualTo("https://gitlab.empresa.com");
        });
        assertThat(client.lastBaseUrl).isEqualTo("https://gitlab.empresa.com");
    }

    @Test
    void eachRepositoryGetsItsOwnDirectoryUnderTheMountPath() {
        // Otherwise a group would collapse into one folder and the clones would fight over it.
        client.repos = List.of(repo("acme/api", "main", false), repo("acme/web", "main", false));
        RepoSpec node = groupNode("acme");
        node.mountPath = "/workspace/";

        assertThat(expander.expandFlat(List.of(node)))
                .extracting(r -> r.mountPath)
                .containsExactly("/workspace/api", "/workspace/web");
    }

    @Test
    void aGroupLargerThanTheLimitIsTruncatedAndSaysSo() {
        expander = new RepoExpander(client, 2);
        client.repos = List.of(repo("acme/a", "main", false), repo("acme/b", "main", false),
                repo("acme/c", "main", false));

        RepoExpander.Expanded result = expander.expand(List.of(groupNode("acme"))).getFirst();

        assertThat(result.repos()).hasSize(2);
        // Silently working on a prefix of the group would look like success.
        assertThat(result.note()).contains("limit of 2");
    }

    @Test
    void aListingFailureIsReportedAgainstTheGroupTheUserTyped() {
        client.failure = new GitProviderClient.GitApiException("403 Forbidden");
        RepoSpec node = groupNode("acme");
        node.credentialId = "cred-1";
        AgentSpecTokens.withLookup(id -> "token", () -> {
            RepoExpander.Expanded result = expander.expand(List.of(node)).getFirst();
            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("acme").contains("403");
            assertThat(result.repos()).isEmpty();
        });
    }

    @Test
    void aMissingTokenIsCalledOutRatherThanLeftAsAnHttpCode() {
        // Far and away the most common cause, and the one whose raw error says least.
        client.failure = new GitProviderClient.GitApiException("404 Not Found");

        RepoExpander.Expanded result = expander.expand(List.of(groupNode("acme"))).getFirst();

        assertThat(result.error()).contains("no token is selected");
    }

    @Test
    void anEmptyGroupIsAnErrorRatherThanASilentlyEmptyRun() {
        RepoExpander.Expanded result = expander.expand(List.of(groupNode("acme"))).getFirst();

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("no repositories found");
    }

    @Test
    void aSelectionThatNoLongerMatchesSaysThatSpecifically() {
        client.repos = List.of(repo("acme/api", "main", false));
        RepoSpec node = groupNode("acme");
        node.only = List.of("acme/deleted");

        assertThat(expander.expand(List.of(node)).getFirst().error())
                .contains("none of the selected repositories");
    }

    @Test
    void severalNodesAreResolvedIndependently() {
        client.repos = List.of(repo("acme/api", "main", false));
        RepoSpec single = new RepoSpec();
        single.url = "https://github.com/other/thing.git";

        List<RepoSpec> repos = expander.expandFlat(Arrays.asList(single, groupNode("acme")));

        assertThat(repos).extracting(r -> r.url)
                .containsExactly("https://github.com/other/thing.git", "https://github.com/acme/api.git");
    }

    /** Installs a credential lookup for the duration of a block, then restores the previous one. */
    private static final class AgentSpecTokens {
        static void withLookup(java.util.function.Function<String, String> lookup, Runnable body) {
            com.concentus.config.AgentSpec.setCredentialLookup(lookup);
            try {
                body.run();
            } finally {
                com.concentus.config.AgentSpec.setCredentialLookup(null);
            }
        }
    }
}
