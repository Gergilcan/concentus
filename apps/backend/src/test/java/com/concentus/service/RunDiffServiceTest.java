package com.concentus.service;

import com.concentus.git.GitWorkspace;
import com.concentus.model.PatchStats;
import com.concentus.model.RunPatch;
import com.concentus.store.RunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The run's diffs as the review screen asks for them. Against a real git for the checkout that
 * exists, because what is under test is that the diff is read from the working tree — commits
 * the agent made since the clone included — and never from the checkout's own index.
 */
class RunDiffServiceTest {

    @TempDir
    Path dir;

    private final RunStore runStore = mock(RunStore.class);
    private final GitWorkspace git = new GitWorkspace(null, true, 30, 0);
    private final RunDiffService service = new RunDiffService(git, runStore);

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private void git(Path in, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(in.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
    }

    /** A checkout with one commit, as the agent receives it. */
    private Path checkout(String name) throws Exception {
        Path repo = dir.resolve(name);
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "t");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("README.md"), "one\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "start");
        return repo;
    }

    @Test
    void reads_the_working_tree_now_including_what_the_agent_committed_since_the_clone() throws Exception {
        assumeTrue(gitAvailable(), "git is not on the PATH");
        Path repo = checkout("repo");
        AgentRun run = new AgentRun("run-1", "flow", "Flow");
        run.recordPatch(RunPatch.registered("coord", "Coordinator", "repo", "https://x/repo.git",
                repo, git.headOf(repo)));

        // Nothing yet: registered, read, unchanged — and said so, not left as "never read".
        List<RunPatch> before = service.diffsOf(run);
        assertThat(before).hasSize(1);
        assertThat(before.get(0).patch()).isNull();
        assertThat(before.get(0).note()).isNull();
        assertThat(before.get(0).takenAt()).isPositive();
        verify(runStore, times(1)).markDirty(run);

        // The coordinator has a shell: it edits, commits one file, and leaves another uncommitted
        // plus one it only created. All three are its work.
        Files.writeString(repo.resolve("README.md"), "one\ntwo\n");
        git(repo, "commit", "-q", "-am", "agent's commit");
        Files.writeString(repo.resolve("NEW.txt"), "brand new\n");
        Files.writeString(repo.resolve("README.md"), "one\ntwo\nthree\n");

        List<RunPatch> diffs = service.diffsOf(run);

        RunPatch d = diffs.get(0);
        assertThat(d.nodeId()).isEqualTo("coord");
        assertThat(d.label()).isEqualTo("Coordinator");
        assertThat(d.folder()).isEqualTo("repo");
        assertThat(d.patch()).contains("+two").contains("+three").contains("NEW.txt").contains("+brand new");
        assertThat(d.stats()).isEqualTo(new PatchStats(2, 3, 0));
        assertThat(d.note()).isNull();
        // Recorded on the run, so the store's next write carries it.
        assertThat(run.patchOf("coord", "repo").patch()).isEqualTo(d.patch());
        verify(runStore, times(2)).markDirty(run);

        // The read left the checkout's own staging alone: NEW.txt is still untracked to git.
        Process status = new ProcessBuilder("git", "status", "--porcelain")
                .directory(repo.toFile()).redirectErrorStream(true).start();
        assertThat(new String(status.getInputStream().readAllBytes())).contains("?? NEW.txt");

        // Same diff again: nothing to write.
        service.diffsOf(run);
        verify(runStore, times(2)).markDirty(run);
    }

    @Test
    void a_checkout_whose_directory_is_gone_serves_the_recorded_patch_and_says_so() {
        AgentRun run = new AgentRun("run-2", "flow", "Flow");
        Path gone = dir.resolve("vanished");
        String patch = "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n";
        run.recordPatch(RunPatch.registered("w1", "Worker", "repo", "https://x/repo.git", gone, null)
                .taken(patch, 5));
        run.recordPatch(RunPatch.registered("w2", "Other", "repo", "https://x/repo.git", gone, null));

        List<RunPatch> diffs = service.diffsOf(run);

        assertThat(diffs).hasSize(2);
        assertThat(diffs.get(0).patch()).isEqualTo(patch);
        assertThat(diffs.get(0).note()).isEqualTo(RunDiffService.GONE_NOTE);
        assertThat(diffs.get(0).stats()).isEqualTo(new PatchStats(1, 1, 1));
        // Never read before it went: unknown, which is not the same as unchanged.
        assertThat(diffs.get(1).patch()).isNull();
        assertThat(diffs.get(1).note()).isEqualTo(RunDiffService.NEVER_READ_NOTE);
        // Nothing was read, so nothing changed, so nothing to persist.
        verify(runStore, never()).markDirty(any());
    }

    @Test
    void the_download_name_says_who_and_which_checkout() {
        RunPatch p = RunPatch.registered("w1", "Data worker", "concentus", null, null, null);
        assertThat(RunDiffService.fileNameOf(p)).isEqualTo("data-worker--concentus.patch");
    }
}
