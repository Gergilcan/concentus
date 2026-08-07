package com.concentus.git;

import com.concentus.config.AgentSpec.RepoSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cloning a flow's repositories.
 *
 * <p>The properties worth pinning are the ones that are invisible when wrong: a clone directory
 * derived from an editable URL, and a token that must not reach disk. Both look fine in a passing
 * run either way.
 */
class GitWorkspaceTest {

    private static GitWorkspace workspace() {
        return new GitWorkspace(expander(), true, 30, 0);
    }

    /** Group expansion needs no network for a plain URL node — it passes those straight through. */
    private static RepoExpander expander() {
        return new RepoExpander(
                new com.concentus.git.GitProviderClient(new com.fasterxml.jackson.databind.ObjectMapper(), 5),
                25);
    }

    private static RepoSpec repo(String url) {
        RepoSpec r = new RepoSpec();
        r.url = url;
        return r;
    }

    // ---- directory naming ----

    @Test
    void theCloneIsNamedAfterTheRepository() {
        assertThat(GitWorkspace.slug("https://github.com/acme/widgets.git")).isEqualTo("widgets");
        assertThat(GitWorkspace.slug("https://gitlab.com/acme/backend/api")).isEqualTo("api");
        assertThat(GitWorkspace.slug("git@github.com:acme/widgets.git")).isEqualTo("widgets");
    }

    @Test
    void aTrailingSlashOrQueryStringDoesNotChangeTheName() {
        assertThat(GitWorkspace.slug("https://github.com/acme/widgets/")).isEqualTo("widgets");
        assertThat(GitWorkspace.slug("https://github.com/acme/widgets?ref=main")).isEqualTo("widgets");
    }

    @Test
    void aTraversalInTheUrlCannotEscapeTheRunDirectory() {
        // The URL is editable over HTTP, so the directory name derived from it is untrusted. A
        // name containing separators or dots would otherwise place the checkout outside the run's
        // own workdir — which is the only reason that directory is safe to hand to the agent.
        assertThat(GitWorkspace.slug("https://evil.example/../../etc/passwd"))
                .doesNotContain("..").doesNotContain("/").doesNotContain("\\");
        assertThat(GitWorkspace.slug("https://evil.example/a/..")).doesNotStartWith(".");
    }

    @Test
    void aUrlWithNoUsableNameStillYieldsADirectory() {
        assertThat(GitWorkspace.slug("https://example.com/")).isNotBlank();
        assertThat(GitWorkspace.slug("...")).isEqualTo("repo");
    }

    // ---- credentials ----

    @Test
    void aRepoWithNoCredentialContributesNoEnvironment() {
        GitWorkspace.Checkout none =
                new GitWorkspace.Checkout(repo("https://x/y"), Path.of("y"), null, null, null);

        assertThat(GitWorkspace.environmentFor(List.of(none))).isEmpty();
    }

    @Test
    void eachRepoGetsItsOwnEnvironmentVariable() {
        // Two repositories can legitimately need two different tokens — one org, one contractor's
        // fork — so a single shared variable would silently send the wrong one.
        GitWorkspace.Checkout a = new GitWorkspace.Checkout(
                repo("https://x/a"), Path.of("a"), "CONCENTUS_GIT_TOKEN_0", "tok-a", null);
        GitWorkspace.Checkout b = new GitWorkspace.Checkout(
                repo("https://x/b"), Path.of("b"), "CONCENTUS_GIT_TOKEN_1", "tok-b", null);

        Map<String, String> env = GitWorkspace.environmentFor(List.of(a, b));

        assertThat(env).containsEntry("CONCENTUS_GIT_TOKEN_0", "tok-a")
                       .containsEntry("CONCENTUS_GIT_TOKEN_1", "tok-b");
    }

    @Test
    void aFailedCheckoutContributesNoCredential() {
        GitWorkspace.Checkout failed = new GitWorkspace.Checkout(
                repo("https://x/y"), Path.of("y"), "CONCENTUS_GIT_TOKEN_0", "tok", "clone failed");

        assertThat(GitWorkspace.environmentFor(List.of(failed))).isEmpty();
    }

    @Test
    void gitIsToldNeverToPrompt() {
        // Without this a wrong or missing credential hangs the run on a hidden username prompt
        // instead of failing — the run just stops producing output and nobody knows why.
        GitWorkspace.Checkout c = new GitWorkspace.Checkout(
                repo("https://x/y"), Path.of("y"), "CONCENTUS_GIT_TOKEN_0", "tok", null);

        assertThat(GitWorkspace.environmentFor(List.of(c)))
                .containsEntry("GIT_TERMINAL_PROMPT", "0");
    }

    // ---- behaviour ----

    @Test
    void nothingIsClonedWhenTheFlowDeclaresNoRepositories(@TempDir Path dir) {
        assertThat(workspace().prepare(List.of(), dir)).isEmpty();
    }

    @Test
    void aRepositoryWithNoUrlIsSkippedRatherThanAttempted(@TempDir Path dir) {
        assertThat(workspace().prepare(List.of(repo("  ")), dir)).isEmpty();
    }

    @Test
    void cloningCanBeSwitchedOffEntirely(@TempDir Path dir) {
        GitWorkspace disabled = new GitWorkspace(expander(), false, 30, 0);

        assertThat(disabled.prepare(List.of(repo("https://github.com/acme/widgets")), dir)).isEmpty();
    }

    @Test
    void aCheckoutReportsTheFolderTheAgentWillSee() {
        GitWorkspace.Checkout c = new GitWorkspace.Checkout(
                repo("https://github.com/acme/widgets.git"), Path.of("/run/widgets"), null, null, null);

        assertThat(c.folderName()).isEqualTo("widgets");
        assertThat(c.ok()).isTrue();
    }
}
