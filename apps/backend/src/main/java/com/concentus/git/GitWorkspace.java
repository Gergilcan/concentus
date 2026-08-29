package com.concentus.git;

import com.concentus.config.AgentSpec.RepoSpec;
import com.concentus.integration.Redact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Clones a flow's repositories into the run's working directory so the agent can edit them.
 *
 * <p>Cloned <em>into the run's own workdir</em>, which is already the CLI's working directory — so
 * the checkout is simply there, with no {@code --add-dir} grant and no entry needed in
 * {@code local.context-roots}. A directory this process created for this run is not the filesystem
 * exposure that allowlist exists to prevent.
 *
 * <p>One clone per run rather than a shared checkout per flow: two runs of the same
 * mail-triggered flow can overlap, and a single working tree would have them writing over each
 * other's branch mid-edit.
 *
 * <h2>Where the token lives</h2>
 * Never in {@code .git/config} and never in the remote URL. A URL-embedded token is written to
 * disk, printed by {@code git remote -v}, and easily copied into a commit message or a PR body by
 * accident. Instead each clone gets a credential helper that reads a per-repo environment
 * variable, and that variable is set on the CLI process.
 *
 * <p>That keeps the token off disk and out of casual output. It does <b>not</b> hide it from the
 * agent: the agent runs with {@code bypassPermissions} and can read its own environment. That is
 * inherent to letting the agent push, and the mitigation is the token's scope — a fine-grained
 * credential limited to these repositories, separate from any other credential this deployment
 * holds.
 */
@Component
public class GitWorkspace {

    private static final Logger log = LoggerFactory.getLogger(GitWorkspace.class);

    private final RepoExpander expander;
    private final boolean enabled;
    private final int cloneTimeoutSeconds;
    private final int depth;

    public GitWorkspace(RepoExpander expander,
                        @Value("${git.clone-enabled:true}") boolean enabled,
                        @Value("${git.clone-timeout-seconds:300}") int cloneTimeoutSeconds,
                        @Value("${git.clone-depth:0}") int depth) {
        this.expander = expander;
        this.enabled = enabled;
        this.cloneTimeoutSeconds = cloneTimeoutSeconds;
        this.depth = depth;
    }

    /**
     * One checked-out repository.
     *
     * @param tokenEnvVar the environment variable the clone's credential helper reads, or null
     *                    when the repo needed no credential
     */
    public record Checkout(RepoSpec spec, Path directory, String tokenEnvVar, String token,
                           String error) {

        public boolean ok() {
            return error == null;
        }

        /** The path the agent should be told about, relative to its working directory. */
        public String folderName() {
            return directory.getFileName().toString();
        }
    }

    /**
     * Clones every repository the flow declares.
     *
     * <p>A repository that fails to clone is reported rather than aborting the run: a flow with
     * three repos and one broken URL should still be able to work on the other two, and the agent
     * is told which are missing.
     */
    public List<Checkout> prepare(List<RepoSpec> repos, Path workdir) {
        List<Checkout> out = new ArrayList<>();
        if (!enabled || repos == null || repos.isEmpty()) return out;

        int index = 0;
        // A node may name a whole organization or group, in which case it stands for every
        // repository inside it — resolved now, so the set reflects the group as it is today.
        for (RepoExpander.Expanded expanded : expander.expand(repos)) {
            if (!expanded.ok()) {
                // Reported as a failed checkout rather than thrown: the agent is told which
                // repositories it does not have, and the rest of the run still happens.
                out.add(new Checkout(expanded.source(), workdir, null, null, expanded.error()));
                continue;
            }
            if (expanded.note() != null) log.warn(expanded.note());
            for (RepoSpec repo : expanded.repos()) {
                String envVar = "CONCENTUS_GIT_TOKEN_" + index++;
                out.add(clone(repo, workdir, envVar));
            }
        }
        return out;
    }

    private Checkout clone(RepoSpec repo, Path workdir, String envVar) {
        String token = repo.resolveToken();
        Path target = workdir.resolve(slug(repo.url));
        try {
            if (Files.exists(target)) {
                // A resumed run already has it; re-cloning would discard the agent's edits.
                return new Checkout(repo, target, token == null ? null : envVar, token, null);
            }
            List<String> args = new ArrayList<>(List.of("git"));
            // `-c` is a git-level option and must precede the subcommand — `git clone -c …` is a
            // different thing entirely and would be rejected.
            if (token != null) {
                args.add("-c");
                args.add("credential.helper=" + helperScript(envVar));
            }
            args.add("clone");
            if (depth > 0) {
                args.add("--depth");
                args.add(String.valueOf(depth));
            }
            if (repo.branch != null && !repo.branch.isBlank()) {
                args.add("--branch");
                args.add(repo.branch.trim());
            }
            args.add(repo.url);
            args.add(target.toString());

            ProcessBuilder pb = new ProcessBuilder(args)
                    .directory(workdir.toFile())
                    .redirectErrorStream(true);
            // Never prompt: without this a missing or wrong credential hangs the run on a hidden
            // username prompt instead of failing.
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            if (token != null) pb.environment().put(envVar, token);

            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            boolean done = p.waitFor(cloneTimeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new Checkout(repo, target, null, null, "clone timed out");
            }
            if (p.exitValue() != 0) {
                return new Checkout(repo, target, null, null, Redact.secrets(firstLine(output)));
            }

            // Written after the clone so the helper lives in the repo's own config rather than
            // being inherited from a global one — and so the token is referenced, not stored.
            if (token != null) writeCredentialHelper(target, envVar);
            log.info("Cloned {} into {}", repo.url, target.getFileName());
            return new Checkout(repo, target, token == null ? null : envVar, token, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Checkout(repo, target, null, null, "interrupted");
        } catch (IOException e) {
            return new Checkout(repo, target, null, null, Redact.secrets(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Everything changed in a checkout since it was cloned, as one patch — new files included,
     * which is why it stages first — or null when nothing changed.
     *
     * <p>This is how a worker's work leaves its workspace. A worker has no shell, so it cannot
     * commit or push; the merge step, which has one, applies the patch into a clone of its own
     * and pushes from there. Binary so an added image survives the trip.
     */
    public String patchOf(Path checkout) {
        try {
            git(checkout, "add", "-A");
            String patch = git(checkout, "diff", "--cached", "--binary");
            return patch == null || patch.isBlank() ? null : patch;
        } catch (IOException e) {
            log.warn("patch of {}: {}", checkout, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The commit a checkout is at right now, or null when git could not say. */
    public String headOf(Path checkout) {
        try {
            String head = git(checkout, "rev-parse", "HEAD");
            return head == null || head.isBlank() ? null : head.trim();
        } catch (IOException e) {
            log.warn("head of {}: {}", checkout, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Everything that differs between {@code base} and the working tree as one patch — commits
     * made since, edits not committed, files only created — or null when nothing does.
     *
     * <p>This is the review's view of a checkout, and unlike {@link #patchOf} it never touches
     * the checkout's own index. The agent whose checkout this is may be halfway through staging
     * exactly what it means to commit, and a person looking at the diff must not rewrite that
     * behind its back. So the staging happens in a throwaway index file: {@code add -A} into it
     * is a full snapshot of the working tree (ignored files excepted), and a cached diff of that
     * against {@code base} is the whole change, new files included, however much of it the agent
     * has committed. {@code HEAD} when no base is known, which is the right answer for a worker:
     * it has no shell, so it has made no commits.
     *
     * @throws IOException when git could not answer — a missing base commit, a corrupt clone —
     *                     because "nothing changed" and "could not tell" must not share a value
     */
    public String patchSince(Path checkout, String base) throws IOException {
        Path index = Files.createTempFile("concentus-review-", ".index");
        try {
            // git creates the index file itself; an existing empty one is "smaller than expected".
            Files.delete(index);
            Map<String, String> env = Map.of("GIT_INDEX_FILE", index.toString());
            git(checkout, env, "add", "-A");
            String patch = git(checkout, env, "diff", "--cached", "--binary",
                    base == null || base.isBlank() ? "HEAD" : base);
            return patch == null || patch.isBlank() ? null : patch;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading " + checkout);
        } finally {
            Files.deleteIfExists(index);
        }
    }

    private String git(Path dir, String... args) throws IOException, InterruptedException {
        return git(dir, Map.of(), args);
    }

    private String git(Path dir, Map<String, String> env, String... args)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().putAll(env);
        Process p = pb.start();
        // UTF-8 explicitly: a patch carries file content, and the platform charset is Cp1252 here.
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!p.waitFor(cloneTimeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git " + args[0] + " timed out");
        }
        if (p.exitValue() != 0) throw new IOException("git " + args[0] + " failed: " + firstLine(out));
        return out;
    }

    private static void writeCredentialHelper(Path repo, String envVar) {
        try {
            new ProcessBuilder("git", "config", "--local", "credential.helper", helperScript(envVar))
                    .directory(repo.toFile())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Could not install the credential helper in {}: {}", repo.getFileName(), e.toString());
        }
    }

    /**
     * A shell helper that answers git's credential prompt from an environment variable.
     *
     * <p>{@code oauth2} as the username is what both GitHub and GitLab accept alongside a token;
     * the password is read at call time, so the value never appears in the config file this line
     * is written into.
     */
    private static String helperScript(String envVar) {
        return "!f() { echo username=oauth2; echo \"password=$" + envVar + "\"; }; f";
    }

    /**
     * Directory name for a clone: the repository's own name.
     *
     * <p>Sanitised because the URL is editable over HTTP — a name containing {@code ..} or a
     * separator would otherwise place the checkout outside the run's workdir.
     */
    static String slug(String url) {
        String s = url.trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        s = s.replaceAll("/+$", "");
        if (s.toLowerCase(Locale.ROOT).endsWith(".git")) s = s.substring(0, s.length() - 4);
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        name = name.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^[.-]+", "");
        return name.isBlank() ? "repo" : name;
    }

    /** Environment entries the CLI process needs so the agent can push. */
    public static Map<String, String> environmentFor(List<Checkout> checkouts) {
        Map<String, String> env = new LinkedHashMap<>();
        for (Checkout c : checkouts) {
            if (c.ok() && c.tokenEnvVar() != null && c.token() != null) {
                env.put(c.tokenEnvVar(), c.token());
            }
        }
        if (!env.isEmpty()) env.put("GIT_TERMINAL_PROMPT", "0");
        return env;
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) return "clone failed";
        int nl = s.indexOf('\n');
        return (nl > 0 ? s.substring(0, nl) : s).trim();
    }
}
