package com.concentus.execution;

import com.concentus.config.AgentSpec.RepoSpec;
import com.concentus.git.GitWorkspace;
import com.concentus.service.ProcessCeiling;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * The machine a run's {@code claude} processes, checkouts and folders are on.
 *
 * <p>The executors compose a run — the instructions, the agents, the MCP config, the argv — and
 * for a long time also assumed the working directory they wrote into, the repositories they
 * cloned and the process they spawned were on this machine. This is that assumption made
 * explicit, so a runner (a machine somebody else operates, connected to this backend) can be the
 * host instead. Everything above this line — the flow compiler, attribution, cost, persistence,
 * approvals — is unchanged by where the process is.
 *
 * <p>Paths are the hub's own: every host is given the run's <em>mirror</em> directory under this
 * backend's data directory, which is where the executors write files. The local host runs there;
 * a remote host ships the mirror over and rewrites the paths. Context folders are the exception —
 * they are the host's own paths and never exist here — which is why they are strings, not
 * {@link Path}s: a Linux path pushed through a Windows {@code Path} comes out with backslashes.
 */
public interface RunHost {

    /** {@code "local"} for this machine, otherwise the runner's id. */
    String id();

    /** How the run log names it. */
    String displayName();

    /** Whether the processes run on this machine. What needs the hub's own CLI asks this first. */
    default boolean isLocal() {
        return false;
    }

    /** The {@code claude} command on that host, if there is one. */
    Optional<String> command();

    /**
     * The ceiling a spawn waits on. The hub's own for the local host; a remote host answers with
     * no limit, because the runner enforces its own before it spawns — counting a process that
     * runs elsewhere against this machine's ceiling would block work the machine is not doing.
     */
    ProcessCeiling ceiling();

    /**
     * Where a process on this host reaches this backend — the per-run tool endpoints, the MCP
     * proxy. Loopback for the local host; for a runner, the address it dialed.
     */
    String toolsBaseUrl();

    /**
     * The folders a run may read, resolved and allow-listed ON THE HOST, as absolute paths there.
     * Rejections are reported, with the reason, and dropped.
     */
    List<String> resolveContextDirs(List<String> requested, BiConsumer<String, String> onRejected);

    /** A referenced CLAUDE.md, read on the host under its allowlist; null when none or rejected. */
    String readClaudeMd(String raw, BiConsumer<String, String> onRejected);

    /** Clones the flow's repositories into {@code workdir} (a mirror path) on the host. */
    List<GitWorkspace.Checkout> prepareClones(List<RepoSpec> repos, Path workdir);

    /** The commit a checkout is at, or null when git could not say. */
    String headOf(Path checkout);

    /** Everything changed in a checkout since it was cloned, staged — or null. See {@link GitWorkspace#patchOf}. */
    String patchOf(Path checkout);

    /** The review's diff of a checkout since {@code base}. See {@link GitWorkspace#patchSince}. */
    String patchSince(Path checkout, String base) throws IOException;

    /**
     * What a {@code RunPatch} stores as the checkout's directory, so the review can re-read it
     * later: the absolute path for the local host, and {@code runner:<id>:<path there>} for a
     * remote one — a path on another machine is meaningless without the machine.
     */
    String patchDirectory(Path checkout);

    /** Spawns the CLI in {@code workdir} with the given environment. Blocking until it is running. */
    Process start(List<String> args, Path workdir, Map<String, String> env) throws IOException;
}
