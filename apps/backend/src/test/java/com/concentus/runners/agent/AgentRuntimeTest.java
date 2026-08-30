package com.concentus.runners.agent;

import com.concentus.git.GitWorkspace;
import com.concentus.git.RepoExpander;
import com.concentus.runners.protocol.Frame;
import com.concentus.service.ContextFolderResolver;
import com.concentus.service.ProcessCeiling;
import com.concentus.support.LocalClaudeSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runner's own machine, request by request: files land where the hub says and nowhere
 * else, a real clone of a real repository answers head and patch questions, folders are checked
 * against the runner's own roots, and a process streams and exits.
 */
class AgentRuntimeTest {

    @TempDir
    Path dir;

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void git(Path in, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(in.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
    }

    private AgentRuntime runtime(Path data, String roots) {
        return new AgentRuntime(data, new LocalClaudeSupport("claude"), new ContextFolderResolver(roots),
                new GitWorkspace(RepoExpander.standalone(), true, 60, 0), ProcessCeiling.unlimited());
    }

    @Test
    void a_sync_writes_the_files_under_the_run_and_refuses_to_escape_it() throws Exception {
        AgentRuntime rt = runtime(dir.resolve("data"), "");

        Frame.SyncResult result = rt.sync(new Frame.WorkspaceSync("r", "run_1", List.of(
                new Frame.FileEntry("CLAUDE.md", "# hi"),
                new Frame.FileEntry(".claude/agents/a.md", "# a"))));

        Path run = dir.resolve("data").resolve("runs").resolve("run_1").toAbsolutePath().normalize();
        assertThat(result.workdir()).isEqualTo(run.toString());
        assertThat(rt.runsRoot()).isEqualTo(run.getParent());
        assertThat(Files.readString(run.resolve("CLAUDE.md"))).isEqualTo("# hi");
        assertThat(Files.readString(run.resolve(".claude").resolve("agents").resolve("a.md"))).isEqualTo("# a");

        assertThatThrownBy(() -> rt.sync(new Frame.WorkspaceSync("r", "run_1", List.of(
                new Frame.FileEntry("../../escape.txt", "x")))))
                .isInstanceOf(IOException.class).hasMessageContaining("outside");
        assertThatThrownBy(() -> rt.sync(new Frame.WorkspaceSync("r", "../etc", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rt.head(new Frame.GitHead("r", dir.resolve("elsewhere").toString())))
                .isInstanceOf(IOException.class).hasMessageContaining("outside");

        rt.delete(new Frame.WorkspaceDelete("r", "run_1"));
        assertThat(Files.exists(run)).isFalse();
    }

    @Test
    void a_clone_answers_head_and_patches_like_the_local_workspace_does() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed here");
        Path origin = dir.resolve("origin");
        Files.createDirectories(origin);
        git(origin, "init", "-q", "-b", "main");
        git(origin, "config", "user.email", "t@t");
        git(origin, "config", "user.name", "t");
        Files.writeString(origin.resolve("a.txt"), "a\n");
        git(origin, "add", "-A");
        git(origin, "commit", "-q", "-m", "first");
        AgentRuntime rt = runtime(dir.resolve("data"), "");

        // As a URL, the way a repository node names one; a bare Windows path would slug as a whole.
        Frame.CloneResult cloned = rt.clone(new Frame.GitClone("r", "run_2", "workers/w",
                List.of(new Frame.RepoEntry(origin.toUri().toString(), null, null, null),
                        new Frame.RepoEntry(dir.resolve("nope").toUri().toString(), null, null, null))));

        assertThat(cloned.checkouts()).hasSize(2);
        Frame.CheckoutEntry ok = cloned.checkouts().get(0);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.folder()).isEqualTo("origin");
        assertThat(ok.head()).isNotBlank();
        Path checkout = Path.of(ok.directory());
        assertThat(checkout).isEqualTo(rt.runsRoot().resolve("run_2").resolve("workers").resolve("w").resolve("origin"));
        assertThat(cloned.checkouts().get(1).ok()).isFalse();
        assertThat(cloned.checkouts().get(1).error()).isNotBlank();

        assertThat(rt.head(new Frame.GitHead("r", ok.directory())).head()).isEqualTo(ok.head());
        assertThat(rt.patchOf(new Frame.GitPatchOf("r", ok.directory())).patch()).isNull();
        Files.writeString(checkout.resolve("a.txt"), "b\n");
        assertThat(rt.patchSince(new Frame.GitPatchSince("r", ok.directory(), ok.head())).patch()).contains("+b");
        assertThat(rt.patchOf(new Frame.GitPatchOf("r", ok.directory())).patch()).contains("+b");
        assertThatThrownBy(() -> rt.patchSince(new Frame.GitPatchSince("r", checkout.resolveSibling("gone").toString(), null)))
                .isInstanceOf(IOException.class).hasMessageContaining("no longer exists");
    }

    @Test
    void folders_are_checked_against_the_runners_own_roots() throws Exception {
        Path root = Files.createDirectories(dir.resolve("srv"));
        Path docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("CLAUDE.md"), "# rules");
        AgentRuntime rt = runtime(dir.resolve("data"), root.toString());

        Frame.ContextResult resolved = rt.resolve(new Frame.ContextResolve("r",
                List.of(docs.toString(), dir.resolve("outside").toString())));
        assertThat(resolved.accepted()).containsExactly(docs.toRealPath().toString());
        assertThat(resolved.rejected()).hasSize(1);
        assertThat(resolved.rejected().get(0).path()).isEqualTo(dir.resolve("outside").toString());

        assertThat(rt.read(new Frame.FsRead("r", docs.toString())).content()).isEqualTo("# rules");
        assertThat(rt.read(new Frame.FsRead("r", dir.resolve("outside").toString())).error()).isNotBlank();

        AgentRuntime none = runtime(dir.resolve("data2"), "");
        assertThat(none.contextRoots()).isEmpty();
        assertThat(none.resolve(new Frame.ContextResolve("r", List.of(docs.toString()))).accepted()).isEmpty();
    }

    @Test
    void a_process_streams_its_lines_exits_and_can_be_stopped() throws Exception {
        AgentRuntime rt = runtime(dir.resolve("data"), "");
        Path workdir = rt.runDir("run_3");
        Files.createDirectories(workdir);
        List<String> args = FakeCli.echoing(dir, "line one", "line two");
        List<String> lines = new CopyOnWriteArrayList<>();
        AtomicInteger exit = new AtomicInteger(Integer.MIN_VALUE);
        CountDownLatch done = new CountDownLatch(1);

        rt.start(new Frame.ProcStart("r", "p1", "run_3", args, workdir.toString(), Map.of("X", "1")),
                lines::add, w -> lines.add("wait:" + w), code -> {
                    exit.set(code);
                    done.countDown();
                });

        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(lines).containsExactly("line one", "line two");
        assertThat(exit.get()).isZero();
        assertThat(rt.busy()).isZero();

        // A process that would run for a while is stopped on request.
        List<String> slow = FakeCli.sleeping(dir, 20);
        CountDownLatch stopped = new CountDownLatch(1);
        rt.start(new Frame.ProcStart("r", "p2", "run_3", slow, workdir.toString(), Map.of()),
                l -> { }, w -> { }, code -> stopped.countDown());
        assertThat(rt.busy()).isEqualTo(1);
        rt.stop("p2");
        assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(rt.busy()).isZero();

        assertThatThrownBy(() -> rt.start(new Frame.ProcStart("r", "p3", "run_3", args,
                dir.resolve("elsewhere").toString(), Map.of()), l -> { }, w -> { }, c -> { }))
                .isInstanceOf(IOException.class).hasMessageContaining("outside");
    }
}
