package com.concentus.runners.agent;

import com.concentus.git.GitWorkspace;
import com.concentus.runners.protocol.Frame;
import com.concentus.service.ContextFolderResolver;
import com.concentus.service.ProcessCeiling;
import com.concentus.support.Ids;
import com.concentus.support.LocalClaudeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * What a runner does on its own machine, request by request: keep a workspace per run, clone into
 * it, answer git about it, resolve folders under its own allowlist, and spawn the CLI.
 *
 * <p>Every path a request names is checked to be inside this runner's run root before anything
 * touches it. The hub is trusted to send well-formed requests, and a hub is also a server on the
 * internet; a runner that wrote wherever it was told would turn one compromised hub into every
 * runner's disk.
 */
public final class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    /** Grace after a soft kill before the process is killed hard. */
    private static final long FORCE_KILL_AFTER_SECONDS = 5;

    private final Path runsRoot;
    private final LocalClaudeSupport support;
    private final ContextFolderResolver contextFolders;
    private final GitWorkspace git;
    private final ProcessCeiling ceiling;
    private final Map<String, Running> running = new ConcurrentHashMap<>();

    private record Running(Process process, ProcessCeiling.Slot slot, Thread reader) {
    }

    public AgentRuntime(Path dataDir, LocalClaudeSupport support, ContextFolderResolver contextFolders,
                        GitWorkspace git, ProcessCeiling ceiling) {
        this.runsRoot = dataDir.resolve("runs").toAbsolutePath().normalize();
        this.support = support;
        this.contextFolders = contextFolders;
        this.git = git;
        this.ceiling = ceiling == null ? ProcessCeiling.unlimited() : ceiling;
    }

    public Path runsRoot() {
        return runsRoot;
    }

    public LocalClaudeSupport support() {
        return support;
    }

    public List<String> contextRoots() {
        return contextFolders == null ? List.of() : contextFolders.roots();
    }

    public int busy() {
        return running.size();
    }

    // ------------------------------------------------------------------ workspace

    public Frame.SyncResult sync(Frame.WorkspaceSync request) throws IOException {
        Path dir = runDir(request.runId());
        Files.createDirectories(dir);
        if (request.files() != null) {
            for (Frame.FileEntry file : request.files()) {
                Path target = inside(dir, dir.resolve(file.path().replace('/', java.io.File.separatorChar)));
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.content() == null ? "" : file.content(), StandardCharsets.UTF_8);
            }
        }
        return new Frame.SyncResult(dir.toString());
    }

    public void delete(Frame.WorkspaceDelete request) throws IOException {
        Path dir = runDir(request.runId());
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ------------------------------------------------------------------ git

    public Frame.CloneResult clone(Frame.GitClone request) throws IOException {
        Path base = runDir(request.runId());
        if (request.subdir() != null && !request.subdir().isBlank()) {
            base = inside(base, base.resolve(request.subdir().replace('/', java.io.File.separatorChar)));
        }
        Files.createDirectories(base);
        List<Frame.CheckoutEntry> out = new ArrayList<>();
        if (request.repos() != null) {
            for (Frame.RepoEntry repo : request.repos()) {
                if (git == null) {
                    out.add(new Frame.CheckoutEntry(repo.url(), null, null, repo.envVar(), null, false,
                            "git is not available on this runner"));
                    continue;
                }
                GitWorkspace.Checkout c = git.cloneResolved(repo.url(), repo.branch(), repo.token(), base, repo.envVar());
                out.add(new Frame.CheckoutEntry(repo.url(), c.folderName(), c.directory().toString(), repo.envVar(),
                        c.ok() ? git.headOf(c.directory()) : null, c.ok(), c.error()));
            }
        }
        return new Frame.CloneResult(out);
    }

    public Frame.HeadResult head(Frame.GitHead request) throws IOException {
        return new Frame.HeadResult(git == null ? null : git.headOf(checkout(request.directory())));
    }

    public Frame.PatchResult patchOf(Frame.GitPatchOf request) throws IOException {
        return new Frame.PatchResult(git == null ? null : git.patchOf(checkout(request.directory())));
    }

    public Frame.PatchResult patchSince(Frame.GitPatchSince request) throws IOException {
        if (git == null) throw new IOException("git is not available on this runner");
        Path dir = checkout(request.directory());
        if (!Files.exists(dir.resolve(".git"))) throw new IOException("The checkout directory no longer exists on the runner.");
        return new Frame.PatchResult(git.patchSince(dir, request.base()));
    }

    // ------------------------------------------------------------------ folders

    public Frame.ContextResult resolve(Frame.ContextResolve request) {
        List<Frame.Rejection> rejected = new ArrayList<>();
        if (contextFolders == null) {
            for (String f : request.folders()) rejected.add(new Frame.Rejection(f, "this runner has no context roots"));
            return new Frame.ContextResult(List.of(), rejected);
        }
        List<String> accepted = contextFolders.resolve(request.folders(), (path, reason) ->
                rejected.add(new Frame.Rejection(path, reason))).stream().map(Path::toString).toList();
        return new Frame.ContextResult(accepted, rejected);
    }

    public Frame.ReadResult read(Frame.FsRead request) {
        if (contextFolders == null) return new Frame.ReadResult(null, "this runner has no context roots");
        String[] reason = new String[1];
        Path file = contextFolders.resolveClaudeMd(request.path(), (path, why) -> reason[0] = why);
        if (file == null) return new Frame.ReadResult(null, reason[0] == null ? "not found" : reason[0]);
        try {
            return new Frame.ReadResult(Files.readString(file), null);
        } catch (IOException e) {
            return new Frame.ReadResult(null, "could not be read: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ processes

    /**
     * Spawns the CLI, after a slot of this runner's own ceiling. Returns once it is running; the
     * output and the exit go to the sinks, from a thread of their own.
     */
    public void start(Frame.ProcStart request, Consumer<String> line, Consumer<String> waiting, IntConsumer exit)
            throws IOException, InterruptedException {
        Path workdir = inside(runsRoot, Path.of(request.workdir()));
        Files.createDirectories(workdir);
        ProcessCeiling.Slot slot = ceiling.acquire(() -> false, waiting);
        if (slot == null) throw new IOException("no process slot");
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(request.args()).directory(workdir.toFile()).redirectErrorStream(true);
            if (request.env() != null) pb.environment().putAll(request.env());
            process = pb.start();
        } catch (IOException | RuntimeException e) {
            slot.close();
            throw e;
        }
        Thread reader = new Thread(() -> pump(request.procId(), process, slot, line, exit), "runner-proc-" + request.procId());
        reader.setDaemon(true);
        running.put(request.procId(), new Running(process, slot, reader));
        reader.start();
    }

    private void pump(String procId, Process process, ProcessCeiling.Slot slot, Consumer<String> line, IntConsumer exit) {
        int code = -1;
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String l;
            while ((l = reader.readLine()) != null) {
                line.accept(l);
            }
            code = process.waitFor();
        } catch (IOException e) {
            log.debug("Process {} output ended: {}", procId, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            slot.close();
            running.remove(procId);
            exit.accept(code);
        }
    }

    public void stdin(Frame.ProcStdin request) {
        Running r = running.get(request.procId());
        if (r == null) return;
        OutputStream in = r.process().getOutputStream();
        try {
            if (request.data() != null && !request.data().isEmpty()) {
                in.write(java.util.Base64.getDecoder().decode(request.data()));
                in.flush();
            }
            if (request.close()) in.close();
        } catch (IOException e) {
            // The process closed its stdin first; its own words about that are on stdout.
            log.debug("stdin of {}: {}", request.procId(), e.getMessage());
        }
    }

    public void stop(String procId) {
        Running r = running.get(procId);
        if (r == null) return;
        r.process().destroy();
        Thread hard = new Thread(() -> {
            try {
                if (!r.process().waitFor(FORCE_KILL_AFTER_SECONDS, TimeUnit.SECONDS)) r.process().destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "runner-kill-" + procId);
        hard.setDaemon(true);
        hard.start();
    }

    public void killAll() {
        for (String id : List.copyOf(running.keySet())) stop(id);
    }

    /** {@code claude --version}, or null when it could not be asked. */
    public String claudeVersion() {
        Optional<String> cmd = support == null ? Optional.empty() : support.command();
        if (cmd.isEmpty()) return null;
        try {
            Process p = new ProcessBuilder(cmd.get(), "--version").redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 && !out.isBlank() ? out.lines().findFirst().orElse(null) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ------------------------------------------------------------------ paths

    Path runDir(String runId) {
        Ids.sanitize(runId, "Not a run id: ");
        return runsRoot.resolve(runId);
    }

    private Path checkout(String directory) throws IOException {
        if (directory == null || directory.isBlank()) throw new IOException("no directory");
        return inside(runsRoot, Path.of(directory));
    }

    private static Path inside(Path root, Path candidate) throws IOException {
        Path abs = candidate.toAbsolutePath().normalize();
        if (!abs.startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException(candidate + " is outside the runner's workspace");
        }
        return abs;
    }
}
