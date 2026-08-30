package com.concentus.runners;

import com.concentus.config.AgentSpec.RepoSpec;
import com.concentus.execution.RunHost;
import com.concentus.git.GitWorkspace;
import com.concentus.git.RepoExpander;
import com.concentus.runners.protocol.Frame;
import com.concentus.runners.protocol.Frames;
import com.concentus.service.ProcessCeiling;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A connected runner as a run host.
 *
 * <p>The executors keep writing into the run's mirror directory here, with the paths they always
 * used. This host ships the mirror's files over before each spawn (only what changed since the
 * last time), rewrites every occurrence of the mirror path — in the argv, in the environment, in
 * the files' own text, in what goes to stdin — to the runner's own directory for the run, and
 * spawns there. Clones happen only on the runner; what comes back is a {@link GitWorkspace.Checkout}
 * whose path is the mirror's, because the executors only ever read its last segment.
 */
public class RemoteRunHost implements RunHost {

    private static final Logger log = LoggerFactory.getLogger(RemoteRunHost.class);

    /** Answers that involve no process: a sync, a head, a patch, a folder check. */
    static final Duration QUICK = Duration.ofSeconds(120);
    /** A clone can be long; the runner's own timeout is the real limit, this is the backstop. */
    static final Duration CLONE = Duration.ofMinutes(15);

    private final RunnerLink link;
    private final ObjectMapper mapper;
    /** Where this backend keeps run mirrors: {@code <data-dir>/local}. */
    private final Path mirrorRoot;
    private final RepoExpander expander;
    private final String publicUrl;
    /** Per run, what has been shipped: relative path → size:mtime. */
    private final Map<String, Map<String, String>> synced = new ConcurrentHashMap<>();

    public RemoteRunHost(RunnerLink link, ObjectMapper mapper, Path mirrorRoot, RepoExpander expander,
                         String publicUrl) {
        this.link = link;
        this.mapper = mapper;
        this.mirrorRoot = mirrorRoot.toAbsolutePath().normalize();
        this.expander = expander;
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim();
    }

    public RunnerLink link() {
        return link;
    }

    @Override
    public String id() {
        return link.runnerId();
    }

    @Override
    public String displayName() {
        return "runner '" + link.runnerName() + "'";
    }

    @Override
    public Optional<String> command() {
        Frame.Hello h = link.hello();
        String cmd = h == null ? null : h.claudeCommand();
        return cmd == null || cmd.isBlank() ? Optional.empty() : Optional.of(cmd);
    }

    /** The runner enforces its own before it spawns; counting here would block work this machine is not doing. */
    @Override
    public ProcessCeiling ceiling() {
        return ProcessCeiling.unlimited();
    }

    @Override
    public String toolsBaseUrl() {
        if (!publicUrl.isBlank()) return trimSlashes(publicUrl);
        Frame.Hello h = link.hello();
        return h == null || h.hubUrl() == null ? "" : trimSlashes(h.hubUrl());
    }

    @Override
    public List<String> resolveContextDirs(List<String> requested, BiConsumer<String, String> onRejected) {
        if (requested == null || requested.isEmpty()) return List.of();
        try {
            Frame.Ack ack = ask(new Frame.ContextResolve(reqId(), requested), QUICK);
            Frame.ContextResult result = Frames.result(mapper, ack, Frame.ContextResult.class);
            if (result == null) return List.of();
            if (result.rejected() != null) {
                for (Frame.Rejection r : result.rejected()) onRejected.accept(r.path(), r.reason());
            }
            return result.accepted() == null ? List.of() : List.copyOf(result.accepted());
        } catch (IOException e) {
            for (String raw : requested) onRejected.accept(raw, e.getMessage());
            return List.of();
        }
    }

    @Override
    public String readClaudeMd(String raw, BiConsumer<String, String> onRejected) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Frame.Ack ack = ask(new Frame.FsRead(reqId(), raw), QUICK);
            Frame.ReadResult result = Frames.result(mapper, ack, Frame.ReadResult.class);
            if (result == null) return null;
            if (result.error() != null) {
                onRejected.accept(raw, result.error());
                return null;
            }
            return result.content();
        } catch (IOException e) {
            onRejected.accept(raw, e.getMessage());
            return null;
        }
    }

    @Override
    public List<GitWorkspace.Checkout> prepareClones(List<RepoSpec> repos, Path workdir) {
        List<GitWorkspace.Checkout> out = new ArrayList<>();
        if (repos == null || repos.isEmpty()) return out;
        String runId = runIdOf(workdir);

        // Groups are resolved here, on the hub, where the provider API and its token are: the
        // runner receives repositories, never a question about them.
        List<RepoSpec> specs = new ArrayList<>();
        List<Frame.RepoEntry> entries = new ArrayList<>();
        int index = 0;
        for (RepoExpander.Expanded expanded : expand(repos)) {
            if (!expanded.ok()) {
                out.add(new GitWorkspace.Checkout(expanded.source(), workdir, null, null, expanded.error()));
                continue;
            }
            for (RepoSpec repo : expanded.repos()) {
                String token = repo.resolveToken();
                String envVar = "CONCENTUS_GIT_TOKEN_" + index++;
                specs.add(repo);
                entries.add(new Frame.RepoEntry(repo.url, repo.branch, token, token == null ? null : envVar));
            }
        }
        if (entries.isEmpty()) return out;

        Frame.CloneResult result;
        try {
            Frame.Ack ack = ask(new Frame.GitClone(reqId(), runId, subdirOf(workdir, runId), entries), CLONE);
            result = Frames.result(mapper, ack, Frame.CloneResult.class);
        } catch (IOException e) {
            for (RepoSpec spec : specs) out.add(new GitWorkspace.Checkout(spec, workdir, null, null, e.getMessage()));
            return out;
        }
        List<Frame.CheckoutEntry> checkouts = result == null || result.checkouts() == null ? List.of() : result.checkouts();
        for (int i = 0; i < specs.size(); i++) {
            RepoSpec spec = specs.get(i);
            Frame.RepoEntry sent = entries.get(i);
            Frame.CheckoutEntry got = i < checkouts.size() ? checkouts.get(i) : null;
            if (got == null) {
                out.add(new GitWorkspace.Checkout(spec, workdir, null, null, "the runner did not report this clone"));
                continue;
            }
            String folder = got.folder() == null || got.folder().isBlank() ? GitWorkspace.slug(spec.url) : got.folder();
            Path dir = workdir.resolve(folder);
            out.add(got.ok()
                    ? new GitWorkspace.Checkout(spec, dir, sent.token() == null ? null : sent.envVar(), sent.token(), null)
                    : new GitWorkspace.Checkout(spec, dir, null, null, got.error() == null ? "clone failed" : got.error()));
        }
        return out;
    }

    private List<RepoExpander.Expanded> expand(List<RepoSpec> repos) {
        if (expander != null) return expander.expand(repos);
        List<RepoExpander.Expanded> out = new ArrayList<>();
        for (RepoSpec spec : repos) {
            if (spec.isGroup()) {
                out.add(new RepoExpander.Expanded(spec, List.of(), "repository groups need the hub's provider client", null));
            } else if (spec.url != null && !spec.url.isBlank()) {
                out.add(new RepoExpander.Expanded(spec, List.of(spec), null, null));
            }
        }
        return out;
    }

    @Override
    public String headOf(Path checkout) {
        try {
            Frame.Ack ack = ask(new Frame.GitHead(reqId(), remotePath(checkout)), QUICK);
            Frame.HeadResult result = Frames.result(mapper, ack, Frame.HeadResult.class);
            return result == null ? null : result.head();
        } catch (IOException e) {
            log.warn("head of {} on {}: {}", checkout, link.runnerName(), e.getMessage());
            return null;
        }
    }

    @Override
    public String patchOf(Path checkout) {
        try {
            Frame.Ack ack = ask(new Frame.GitPatchOf(reqId(), remotePath(checkout)), QUICK);
            Frame.PatchResult result = Frames.result(mapper, ack, Frame.PatchResult.class);
            return result == null ? null : result.patch();
        } catch (IOException e) {
            log.warn("patch of {} on {}: {}", checkout, link.runnerName(), e.getMessage());
            return null;
        }
    }

    @Override
    public String patchSince(Path checkout, String base) throws IOException {
        return patchSinceRemote(remotePath(checkout), base);
    }

    /** As {@link #patchSince}, for a directory already named the runner's way — what a stored patch carries. */
    public String patchSinceRemote(String directory, String base) throws IOException {
        Frame.Ack ack = ask(new Frame.GitPatchSince(reqId(), directory, base), QUICK);
        Frame.PatchResult result = Frames.result(mapper, ack, Frame.PatchResult.class);
        return result == null ? null : result.patch();
    }

    @Override
    public String patchDirectory(Path checkout) {
        return "runner:" + link.runnerId() + ":" + remotePath(checkout);
    }

    @Override
    public Process start(List<String> args, Path workdir, Map<String, String> env) throws IOException {
        String runId = runIdOf(workdir);
        sync(runId);
        String procId = UUID.randomUUID().toString();
        RemoteProcess process = new RemoteProcess(link, procId, s -> rewrite(s, runId));
        link.attachProcess(procId, process);
        List<String> remoteArgs = args.stream().map(a -> rewrite(a, runId)).toList();
        Map<String, String> remoteEnv = new LinkedHashMap<>();
        if (env != null) env.forEach((k, v) -> remoteEnv.put(k, rewrite(v, runId)));
        Frame.ProcStart start = new Frame.ProcStart(reqId(), procId, runId, remoteArgs, remotePath(workdir), remoteEnv);
        Frame.Ack ack;
        try {
            // No timeout: the runner acks once it has a slot and the process exists, which can be a
            // long wait behind other runs — exactly the wait the local ceiling imposes. The one way
            // out is the link closing, which fails the future.
            ack = link.request(start.reqId(), start).get();
        } catch (ExecutionException e) {
            link.detachProcess(procId);
            throw new IOException(e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            link.detachProcess(procId);
            throw new IOException("interrupted while starting on " + link.runnerName());
        }
        if (!ack.ok()) {
            link.detachProcess(procId);
            throw new IOException(ack.error() == null ? "the runner refused to start the process" : ack.error());
        }
        return process;
    }

    // ------------------------------------------------------------------ the mirror

    /**
     * Ships what changed in the run's mirror since the last sync. Always sends on a run's first
     * sync, even with nothing to ship, so the runner creates the directory the process will run in.
     */
    void sync(String runId) throws IOException {
        Path dir = mirrorDir(runId);
        boolean first = !synced.containsKey(runId);
        Map<String, String> seen = synced.computeIfAbsent(runId, k -> new ConcurrentHashMap<>());
        List<Frame.FileEntry> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path f : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(f)) continue;
                    String rel = dir.relativize(f).toString().replace(File.separatorChar, '/');
                    String stamp = Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis();
                    if (stamp.equals(seen.get(rel))) continue;
                    String content;
                    try {
                        content = Files.readString(f);
                    } catch (MalformedInputException e) {
                        // The mirror holds what the executors write — instructions, configs, patches,
                        // all text. Something else got there some other way, and is not the run's.
                        log.debug("Skipping {} in the mirror of {}: not text", rel, runId);
                        continue;
                    }
                    files.add(new Frame.FileEntry(rel, rewrite(content, runId)));
                    seen.put(rel, stamp);
                }
            }
        }
        if (files.isEmpty() && !first) return;
        Frame.Ack ack = ask(new Frame.WorkspaceSync(reqId(), runId, files), QUICK);
        Frame.SyncResult result = Frames.result(mapper, ack, Frame.SyncResult.class);
        if (result != null && result.workdir() != null && !result.workdir().equals(remoteDir(runId))) {
            log.warn("Runner {} keeps run {} at {} rather than the {} its hello implied.",
                    link.runnerName(), runId, result.workdir(), remoteDir(runId));
        }
    }

    Path mirrorDir(String runId) {
        return mirrorRoot.resolve(runId).toAbsolutePath().normalize();
    }

    String remoteDir(String runId) {
        Frame.Hello h = link.hello();
        String sep = h == null || h.fileSeparator() == null || h.fileSeparator().isBlank() ? "/" : h.fileSeparator();
        String root = h == null || h.workdirRoot() == null ? "" : h.workdirRoot();
        while (root.endsWith("/") || root.endsWith("\\")) root = root.substring(0, root.length() - 1);
        return root + sep + runId;
    }

    /** The run a mirror path belongs to: the first segment under the mirror root. */
    String runIdOf(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        if (!abs.startsWith(mirrorRoot) || abs.equals(mirrorRoot)) {
            throw new IllegalArgumentException(path + " is not inside a run's mirror (" + mirrorRoot + ")");
        }
        return mirrorRoot.relativize(abs).getName(0).toString();
    }

    private String subdirOf(Path workdir, String runId) {
        Path rel = mirrorDir(runId).relativize(workdir.toAbsolutePath().normalize());
        String s = rel.toString();
        return s.isEmpty() || s.equals(".") ? "" : s.replace(File.separatorChar, '/');
    }

    /** A mirror path as the runner names it. */
    String remotePath(Path path) {
        return rewrite(path.toAbsolutePath().normalize().toString(), runIdOf(path));
    }

    /**
     * Every occurrence of the run's mirror directory in {@code text}, as the runner's directory,
     * separators included. The suffix after the root is this backend's own naming — a run id,
     * {@code workers}, a sanitised agent name — so it never contains whitespace or quotes, which is
     * what bounds the match. Both the plain and the JSON-escaped spelling of a Windows path are
     * handled: a mirror path inside a JSON document carries doubled backslashes.
     */
    String rewrite(String text, String runId) {
        if (text == null || text.isEmpty()) return text;
        String local = mirrorDir(runId).toString();
        String remote = remoteDir(runId);
        Frame.Hello h = link.hello();
        String remoteSep = h == null || h.fileSeparator() == null || h.fileSeparator().isBlank() ? "/" : h.fileSeparator();
        String out = replaceAll(text, local, File.separator, remote, remoteSep);
        if (File.separator.equals("\\")) {
            out = replaceAll(out, local.replace("\\", "\\\\"), "\\\\", remote.replace("\\", "\\\\"),
                    remoteSep.equals("\\") ? "\\\\" : remoteSep);
        }
        return out;
    }

    private static String replaceAll(String text, String local, String localSep, String remote, String remoteSep) {
        if (!text.contains(local)) return text;
        Matcher m = Pattern.compile(Pattern.quote(local) + "([^\\s\"'`,;<>|]*)").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String suffix = m.group(1).replace(localSep, remoteSep);
            m.appendReplacement(sb, Matcher.quoteReplacement(remote + suffix));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ plumbing

    private static String reqId() {
        return UUID.randomUUID().toString();
    }

    private Frame.Ack ask(Frame frame, Duration timeout) throws IOException {
        String reqId = reqIdOf(frame);
        try {
            Frame.Ack ack = link.request(reqId, frame).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!ack.ok()) throw new IOException(ack.error() == null ? "the runner refused" : ack.error());
            return ack;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof IOException io ? io : new IOException(cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new IOException("Runner '" + link.runnerName() + "' did not answer within " + timeout.toSeconds() + " s.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for runner '" + link.runnerName() + "'");
        }
    }

    private static String reqIdOf(Frame frame) {
        return switch (frame) {
            case Frame.WorkspaceSync f -> f.reqId();
            case Frame.GitClone f -> f.reqId();
            case Frame.GitHead f -> f.reqId();
            case Frame.GitPatchOf f -> f.reqId();
            case Frame.GitPatchSince f -> f.reqId();
            case Frame.ContextResolve f -> f.reqId();
            case Frame.FsRead f -> f.reqId();
            case Frame.ProcStart f -> f.reqId();
            case Frame.WorkspaceDelete f -> f.reqId();
            default -> throw new IllegalArgumentException(frame.getClass().getSimpleName() + " is not a request");
        };
    }

    private static String trimSlashes(String s) {
        String out = s.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
