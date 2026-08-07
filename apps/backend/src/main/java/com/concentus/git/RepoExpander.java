package com.concentus.git;

import com.concentus.config.AgentSpec.RepoSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a repository node that names a <em>group</em> into the repositories it stands for.
 *
 * <p>A node can be one repository or a whole GitHub organization / GitLab group. The group is
 * resolved when the run starts, not when the flow is saved: a repository added to the group next
 * week is picked up without anyone reopening the canvas. That is the point of pointing at a group
 * rather than pasting twelve URLs.
 *
 * <p>Selecting a subset stays possible — {@link RepoSpec#only} pins the node to specific
 * repositories — so "everything in the group" and "these four" are the same node type.
 */
@Component
public class RepoExpander {

    private static final Logger log = LoggerFactory.getLogger(RepoExpander.class);

    private final GitProviderClient client;
    private final int maxPerGroup;

    public RepoExpander(GitProviderClient client,
                        @Value("${git.max-group-repos:25}") int maxPerGroup) {
        this.client = client;
        this.maxPerGroup = Math.max(1, maxPerGroup);
    }

    /** For the standalone CLI entry point, which runs a YAML spec outside the Spring context. */
    public static RepoExpander standalone() {
        return new RepoExpander(
                new GitProviderClient(new com.fasterxml.jackson.databind.ObjectMapper(), 30), 25);
    }

    /**
     * One node's worth of repositories.
     *
     * @param source the node as configured, kept so a failure can be reported against the thing
     *               the user actually typed rather than against a derived URL
     * @param repos  what to clone or mount; a single-repository node yields itself
     * @param error  why the group could not be listed, or null
     * @param note   something worth telling the agent even on success — currently a truncated group
     */
    public record Expanded(RepoSpec source, List<RepoSpec> repos, String error, String note) {

        public boolean ok() {
            return error == null;
        }
    }

    /** Resolves every node, leaving single-repository nodes untouched. */
    public List<Expanded> expand(List<RepoSpec> specs) {
        List<Expanded> out = new ArrayList<>();
        if (specs == null) return out;
        for (RepoSpec spec : specs) {
            if (spec.isGroup()) {
                out.add(expandGroup(spec));
            } else if (spec.url != null && !spec.url.isBlank()) {
                out.add(new Expanded(spec, List.of(spec), null, null));
            }
            // A node with neither a URL nor a group is simply unconfigured: skip it silently, the
            // way an empty URL was always skipped.
        }
        return out;
    }

    /** Every resolved repository across all nodes, for callers that do not report per-node. */
    public List<RepoSpec> expandFlat(List<RepoSpec> specs) {
        List<RepoSpec> out = new ArrayList<>();
        for (Expanded e : expand(specs)) {
            if (e.ok()) {
                out.addAll(e.repos());
            } else {
                log.warn("Could not expand group '{}': {}", e.source().group, e.error());
            }
        }
        return out;
    }

    private Expanded expandGroup(RepoSpec spec) {
        List<GitProviderClient.RemoteRepo> found;
        try {
            found = client.listGroupRepos(spec.provider, spec.group, spec.resolveToken(), spec.baseUrl);
        } catch (RuntimeException e) {
            return new Expanded(spec, List.of(), describe(spec, e), null);
        }

        Set<String> wanted = normalisedNames(spec.only);
        List<RepoSpec> repos = new ArrayList<>();
        String note = null;
        for (GitProviderClient.RemoteRepo r : found) {
            if (!wanted.isEmpty() && !matches(wanted, r)) continue;
            if (r.archived() && !spec.includeArchived) continue;
            if (repos.size() >= maxPerGroup) {
                // Cloning a hundred repositories would take longer than the run and fill the disk.
                // Say so rather than quietly working on a prefix of the group.
                note = "Group '" + spec.group + "' has more repositories than the per-run limit of "
                        + maxPerGroup + "; only the first " + maxPerGroup + " were included. "
                        + "Select the ones you need, or raise git.max-group-repos.";
                log.warn(note);
                break;
            }
            repos.add(derive(spec, r));
        }

        if (repos.isEmpty()) {
            String why = wanted.isEmpty()
                    ? "no repositories found in '" + spec.group + "'"
                    : "none of the selected repositories are in '" + spec.group + "' any more";
            return new Expanded(spec, List.of(), why, null);
        }
        log.info("Group '{}' resolved to {} repositor{}", spec.group, repos.size(),
                repos.size() == 1 ? "y" : "ies");
        return new Expanded(spec, repos, null, note);
    }

    /** A concrete repository carrying the node's credential and settings. */
    private static RepoSpec derive(RepoSpec parent, GitProviderClient.RemoteRepo r) {
        RepoSpec spec = new RepoSpec();
        spec.provider = parent.provider;
        spec.url = r.cloneUrl();
        spec.credentialId = parent.credentialId;
        spec.baseUrl = parent.baseUrl;
        // The node's branch, when set, applies to every repository in the group — but a group's
        // repositories rarely share a branch name, so the repository's own default is the sane
        // fallback rather than a hardcoded "main" that would fail the clone.
        spec.branch = parent.branch != null && !parent.branch.isBlank()
                ? parent.branch : r.defaultBranch();
        // Each repository gets its own directory under the node's mount path, so a group does not
        // collapse into one folder.
        if (parent.mountPath != null && !parent.mountPath.isBlank()) {
            spec.mountPath = parent.mountPath.replaceAll("/+$", "") + "/" + r.name();
        }
        return spec;
    }

    /**
     * Accepts either a full name ({@code acme/api}) or a bare name ({@code api}), because a person
     * reading the list sees both and either is an unambiguous way to mean the same repository.
     */
    private static boolean matches(Set<String> wanted, GitProviderClient.RemoteRepo r) {
        return wanted.contains(lower(r.fullName())) || wanted.contains(lower(r.name()));
    }

    private static Set<String> normalisedNames(List<String> only) {
        Set<String> out = new LinkedHashSet<>();
        if (only == null) return out;
        for (String s : only) {
            if (s != null && !s.isBlank()) out.add(lower(s));
        }
        return out;
    }

    private static String lower(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String describe(RepoSpec spec, RuntimeException e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        if (spec.resolveToken() == null) {
            // The overwhelmingly common cause, and the one whose error text is least helpful.
            return "Could not list '" + spec.group + "': " + message
                    + " (no token is selected on this node — a private group needs one).";
        }
        return "Could not list '" + spec.group + "': " + message;
    }
}
