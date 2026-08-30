package com.concentus.runners;

import com.concentus.config.AgentSpec.RepoSpec;
import com.concentus.git.GitWorkspace;
import com.concentus.runners.protocol.Frame;
import com.concentus.runners.protocol.Frames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The remote host against an in-memory link that answers what a Linux runner would: the mirror
 * ships and re-ships only what changed, every mirror path in the argv, the environment, the files
 * and stdin comes out as the runner's own, the process streams and exits, and a dropped link ends
 * everything honestly.
 */
class RemoteRunHostTest {

    @TempDir
    Path data;

    private final ObjectMapper mapper = new ObjectMapper();

    /** A runner on Linux, keeping runs under {@code /home/r/runs}, answering every request at once. */
    final class FakeLink implements RunnerLink {
        final List<Frame> sent = new ArrayList<>();
        final Map<String, ProcessListener> processes = new java.util.HashMap<>();
        Function<Frame, Frame.Ack> answers = f -> ack(f, null);
        boolean open = true;

        @Override
        public String runnerId() {
            return "rn_1";
        }

        @Override
        public String runnerName() {
            return "nas";
        }

        @Override
        public Frame.Hello hello() {
            return new Frame.Hello("0.1", "Linux", "amd64", "nas", "25", "/usr/bin/claude", true, "2.1", "subscription",
                    4, "/", "/home/r/runs", "https://hub.example", List.of("/srv"), "nas");
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public CompletableFuture<Frame.Ack> request(String reqId, Frame frame) {
            sent.add(frame);
            if (!open) return CompletableFuture.failedFuture(new IOException("Runner 'nas' is disconnected."));
            return CompletableFuture.completedFuture(answers.apply(frame));
        }

        @Override
        public void send(Frame frame) {
            sent.add(frame);
        }

        @Override
        public void attachProcess(String procId, ProcessListener listener) {
            processes.put(procId, listener);
        }

        @Override
        public void detachProcess(String procId) {
            processes.remove(procId);
        }

        @Override
        public int busy() {
            return processes.size();
        }

        <T extends Frame> T last(Class<T> type) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (type.isInstance(sent.get(i))) return type.cast(sent.get(i));
            }
            throw new AssertionError("no " + type.getSimpleName() + " was sent");
        }

        <T extends Frame> List<T> all(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    private Frame.Ack ack(Frame request, Object result) {
        String reqId = switch (request) {
            case Frame.WorkspaceSync f -> f.reqId();
            case Frame.GitClone f -> f.reqId();
            case Frame.GitHead f -> f.reqId();
            case Frame.GitPatchOf f -> f.reqId();
            case Frame.GitPatchSince f -> f.reqId();
            case Frame.ContextResolve f -> f.reqId();
            case Frame.FsRead f -> f.reqId();
            case Frame.ProcStart f -> f.reqId();
            default -> "?";
        };
        return new Frame.Ack(reqId, true, null, Frames.result(mapper, result));
    }

    private RemoteRunHost host(FakeLink link) {
        return new RemoteRunHost(link, mapper, data.resolve("local"), null, "");
    }

    private Path mirror(String runId) throws IOException {
        Path dir = data.resolve("local").resolve(runId);
        Files.createDirectories(dir);
        return dir.toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ paths

    @Test
    void mirror_paths_become_the_runners_own_separators_included() throws Exception {
        FakeLink link = new FakeLink();
        RemoteRunHost host = host(link);
        Path mirror = mirror("run_1");
        Path worker = mirror.resolve("workers").resolve("a");

        assertThat(host.remoteDir("run_1")).isEqualTo("/home/r/runs/run_1");
        assertThat(host.remotePath(worker)).isEqualTo("/home/r/runs/run_1/workers/a");
        assertThat(host.rewrite("--add-dir " + worker + " done", "run_1"))
                .isEqualTo("--add-dir /home/r/runs/run_1/workers/a done");
        assertThat(host.rewrite("Their workspaces are under: " + mirror.resolve("workers") + "\n", "run_1"))
                .isEqualTo("Their workspaces are under: /home/r/runs/run_1/workers\n");
        // A path that is not the mirror's is left alone.
        assertThat(host.rewrite("/etc/passwd " + data, "run_1")).isEqualTo("/etc/passwd " + data);
        assertThat(host.patchDirectory(worker.resolve("repo"))).isEqualTo("runner:rn_1:/home/r/runs/run_1/workers/a/repo");
        assertThat(host.runIdOf(worker)).isEqualTo("run_1");
        assertThatThrownBy(() -> host.runIdOf(data.resolve("elsewhere"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_json_escaped_windows_path_is_rewritten_too() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(File.separator.equals("\\"));
        FakeLink link = new FakeLink();
        RemoteRunHost host = host(link);
        Path mirror = mirror("run_1");
        String json = mapper.writeValueAsString(Map.of("cwd", mirror.resolve("merge").toString()));

        assertThat(host.rewrite(json, "run_1")).isEqualTo("{\"cwd\":\"/home/r/runs/run_1/merge\"}");
    }

    // ------------------------------------------------------------------ the process

    @Test
    void start_ships_the_mirror_rewrites_the_spawn_and_streams_the_process_back() throws Exception {
        FakeLink link = new FakeLink();
        RemoteRunHost host = host(link);
        Path mirror = mirror("run_1");
        Files.writeString(mirror.resolve("CLAUDE.md"), "Work in " + mirror.resolve("repo") + "\n");
        Files.createDirectories(mirror.resolve(".claude").resolve("agents"));
        Files.writeString(mirror.resolve(".claude").resolve("agents").resolve("a.md"), "# a");

        Process proc = host.start(List.of("claude", "-p", "hi", "--mcp-config", mirror.resolve("mcp-config.json").toString()),
                mirror, Map.of("CONCENTUS_GIT_TOKEN_0", "tok", "NOTE", mirror.toString()));

        Frame.WorkspaceSync sync = link.last(Frame.WorkspaceSync.class);
        assertThat(sync.runId()).isEqualTo("run_1");
        assertThat(sync.files()).extracting(Frame.FileEntry::path).containsExactlyInAnyOrder("CLAUDE.md", ".claude/agents/a.md");
        assertThat(sync.files()).filteredOn(f -> f.path().equals("CLAUDE.md")).singleElement()
                .extracting(Frame.FileEntry::content).isEqualTo("Work in /home/r/runs/run_1/repo\n");

        Frame.ProcStart start = link.last(Frame.ProcStart.class);
        assertThat(start.args()).containsExactly("claude", "-p", "hi", "--mcp-config", "/home/r/runs/run_1/mcp-config.json");
        assertThat(start.workdir()).isEqualTo("/home/r/runs/run_1");
        assertThat(start.env()).containsEntry("CONCENTUS_GIT_TOKEN_0", "tok").containsEntry("NOTE", "/home/r/runs/run_1");
        assertThat(link.processes).containsKey(start.procId());
        assertThat(proc.isAlive()).isTrue();

        // stdin: what is written is sent on flush, rewritten, and close ends the input.
        proc.getOutputStream().write(("prompt mentions " + mirror.resolve("workers")).getBytes(StandardCharsets.UTF_8));
        proc.getOutputStream().flush();
        proc.getOutputStream().close();
        List<Frame.ProcStdin> stdin = link.all(Frame.ProcStdin.class);
        assertThat(stdin).hasSize(2);
        assertThat(new String(java.util.Base64.getDecoder().decode(stdin.get(0).data()), StandardCharsets.UTF_8))
                .isEqualTo("prompt mentions /home/r/runs/run_1/workers");
        assertThat(stdin.get(1).close()).isTrue();

        // stdout: lines and a log line arrive as the process's output; exit ends it.
        RunnerLink.ProcessListener listener = link.processes.get(start.procId());
        listener.line("{\"type\":\"system\"}");
        listener.log("waiting for a slot");
        listener.line("{\"type\":\"result\"}");
        listener.exit(0);
        BufferedReader reader = proc.inputReader(StandardCharsets.UTF_8);
        assertThat(reader.readLine()).isEqualTo("{\"type\":\"system\"}");
        assertThat(reader.readLine()).isEqualTo("waiting for a slot");
        assertThat(reader.readLine()).isEqualTo("{\"type\":\"result\"}");
        assertThat(reader.readLine()).isNull();
        assertThat(proc.waitFor()).isZero();
        assertThat(proc.isAlive()).isFalse();
        assertThat(link.processes).isEmpty();

        // A second spawn ships only what changed.
        Files.writeString(mirror.resolve("CLAUDE.md"), "changed\n");
        link.sent.clear();
        host.start(List.of("claude"), mirror, Map.of());
        assertThat(link.last(Frame.WorkspaceSync.class).files()).extracting(Frame.FileEntry::path).containsExactly("CLAUDE.md");
        // And nothing at all when nothing did — no sync frame, just the spawn.
        link.sent.clear();
        host.start(List.of("claude"), mirror, Map.of());
        assertThat(link.all(Frame.WorkspaceSync.class)).isEmpty();
        assertThat(link.all(Frame.ProcStart.class)).hasSize(1);
    }

    @Test
    void destroy_asks_the_runner_to_stop_and_a_refused_start_is_an_io_exception() throws Exception {
        FakeLink link = new FakeLink();
        RemoteRunHost host = host(link);
        Path mirror = mirror("run_2");

        Process proc = host.start(List.of("claude"), mirror, Map.of());
        proc.destroy();
        String procId = link.last(Frame.ProcStart.class).procId();
        assertThat(link.last(Frame.ProcStop.class).procId()).isEqualTo(procId);
        // What the runner does after killing it: the exit arrives, and only then is it gone here.
        assertThat(link.processes).containsKey(procId);
        link.processes.get(procId).exit(143);
        assertThat(proc.waitFor()).isEqualTo(143);

        link.answers = f -> f instanceof Frame.ProcStart p ? new Frame.Ack(p.reqId(), false, "no CLI here", null) : ack(f, null);
        assertThatThrownBy(() -> host.start(List.of("claude"), mirror, Map.of()))
                .isInstanceOf(IOException.class).hasMessage("no CLI here");
        assertThat(link.processes).isEmpty();
    }

    @Test
    void a_link_that_closes_fails_the_spawn_and_ends_a_running_process_with_minus_one() throws Exception {
        FakeLink link = new FakeLink();
        RemoteRunHost host = host(link);
        Path mirror = mirror("run_3");
        Process proc = host.start(List.of("claude"), mirror, Map.of());
        RunnerLink.ProcessListener listener = link.processes.get(link.last(Frame.ProcStart.class).procId());

        // What RunnerConnection.close does to every attached process.
        listener.log("Runner 'nas' disconnected");
        listener.exit(-1);
        assertThat(proc.waitFor(1, TimeUnit.SECONDS)).isTrue();
        assertThat(proc.exitValue()).isEqualTo(-1);

        link.open = false;
        assertThatThrownBy(() -> host.start(List.of("claude"), mirror, Map.of())).isInstanceOf(IOException.class)
                .hasMessageContaining("disconnected");
    }

    // ------------------------------------------------------------------ git and folders

    @Test
    void clones_are_asked_of_the_runner_with_the_token_and_come_back_as_checkouts_on_the_mirror() throws Exception {
        FakeLink link = new FakeLink();
        link.answers = f -> f instanceof Frame.GitClone c ? ack(c, new Frame.CloneResult(List.of(
                new Frame.CheckoutEntry(c.repos().get(0).url(), "api", "/home/r/runs/run_4/workers/a/api",
                        c.repos().get(0).envVar(), "abc123", true, null),
                new Frame.CheckoutEntry(c.repos().get(1).url(), null, null, null, null, false, "clone timed out"))))
                : ack(f, null);
        RemoteRunHost host = host(link);
        Path worker = mirror("run_4").resolve("workers").resolve("a");
        RepoSpec api = new RepoSpec();
        api.url = "https://github.com/acme/api.git";
        api.branch = "main";
        RepoSpec web = new RepoSpec();
        web.url = "https://github.com/acme/web.git";

        List<GitWorkspace.Checkout> checkouts = host.prepareClones(List.of(api, web), worker);

        Frame.GitClone sent = link.last(Frame.GitClone.class);
        assertThat(sent.runId()).isEqualTo("run_4");
        assertThat(sent.subdir()).isEqualTo("workers/a");
        assertThat(sent.repos()).extracting(Frame.RepoEntry::url).containsExactly(api.url, web.url);
        assertThat(sent.repos().get(0).branch()).isEqualTo("main");
        assertThat(checkouts).hasSize(2);
        assertThat(checkouts.get(0).ok()).isTrue();
        assertThat(checkouts.get(0).folderName()).isEqualTo("api");
        assertThat(checkouts.get(0).directory()).isEqualTo(worker.resolve("api"));
        assertThat(checkouts.get(1).ok()).isFalse();
        assertThat(checkouts.get(1).error()).isEqualTo("clone timed out");
        assertThat(host.patchDirectory(checkouts.get(0).directory())).isEqualTo("runner:rn_1:/home/r/runs/run_4/workers/a/api");
    }

    @Test
    void git_questions_and_folder_checks_go_to_the_runner_as_its_own_paths() throws Exception {
        FakeLink link = new FakeLink();
        link.answers = f -> switch (f) {
            case Frame.GitHead h -> ack(h, new Frame.HeadResult("abc"));
            case Frame.GitPatchOf p -> ack(p, new Frame.PatchResult("diff"));
            case Frame.GitPatchSince p -> "abc".equals(p.base()) ? ack(p, new Frame.PatchResult(null))
                    : new Frame.Ack(p.reqId(), false, "bad base", null);
            case Frame.ContextResolve c -> ack(c, new Frame.ContextResult(List.of("/srv/docs"),
                    List.of(new Frame.Rejection("/etc", "outside the configured context roots"))));
            case Frame.FsRead r -> r.path().endsWith("nope") ? ack(r, new Frame.ReadResult(null, "no CLAUDE.md found there"))
                    : ack(r, new Frame.ReadResult("# rules", null));
            default -> ack(f, null);
        };
        RemoteRunHost host = host(link);
        Path repo = mirror("run_5").resolve("repo");

        assertThat(host.headOf(repo)).isEqualTo("abc");
        assertThat(link.last(Frame.GitHead.class).directory()).isEqualTo("/home/r/runs/run_5/repo");
        assertThat(host.patchOf(repo)).isEqualTo("diff");
        assertThat(host.patchSince(repo, "abc")).isNull();
        assertThatThrownBy(() -> host.patchSince(repo, "zzz")).isInstanceOf(IOException.class).hasMessage("bad base");
        assertThat(host.patchSinceRemote("/home/r/runs/run_5/repo", "abc")).isNull();

        List<String> rejected = new ArrayList<>();
        assertThat(host.resolveContextDirs(List.of("/srv/docs", "/etc"), (p, why) -> rejected.add(p + ": " + why)))
                .containsExactly("/srv/docs");
        assertThat(rejected).containsExactly("/etc: outside the configured context roots");
        assertThat(host.readClaudeMd("/srv/docs", (p, why) -> rejected.add(why))).isEqualTo("# rules");
        assertThat(host.readClaudeMd("/srv/nope", (p, why) -> rejected.add(why))).isNull();
        assertThat(rejected).contains("no CLAUDE.md found there");
        assertThat(host.resolveContextDirs(List.of(), (p, why) -> rejected.add(why))).isEmpty();
    }

    @Test
    void the_tools_base_url_is_where_the_runner_dialed_unless_a_public_url_says_otherwise() {
        FakeLink link = new FakeLink();
        assertThat(host(link).toolsBaseUrl()).isEqualTo("https://hub.example");
        assertThat(new RemoteRunHost(link, mapper, data, null, "https://concentus.acme.com/ ").toolsBaseUrl())
                .isEqualTo("https://concentus.acme.com");
        assertThat(host(link).command()).contains("/usr/bin/claude");
        assertThat(host(link).displayName()).isEqualTo("runner 'nas'");
        assertThat(host(link).isLocal()).isFalse();
    }
}
