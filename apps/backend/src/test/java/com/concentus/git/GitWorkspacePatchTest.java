package com.concentus.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * How a worker's work leaves its workspace: as a patch of everything it changed, new files
 * included. Against a real git, because the thing under test is the git invocation.
 */
class GitWorkspacePatchTest {

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

    private void git(String... args) throws IOException, InterruptedException {
        java.util.List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
    }

    @Test
    void the_patch_carries_edits_and_new_files_and_is_null_when_nothing_changed() throws Exception {
        assumeTrue(gitAvailable(), "git is not on the PATH");
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        Files.writeString(dir.resolve("README.md"), "one\n");
        git("add", "-A");
        git("commit", "-q", "-m", "start");

        GitWorkspace workspace = new GitWorkspace(null, true, 30, 0);
        assertThat(workspace.patchOf(dir)).isNull();

        Files.writeString(dir.resolve("README.md"), "one\ntwo\n");
        Files.writeString(dir.resolve("NEW.txt"), "brand new\n");

        String patch = workspace.patchOf(dir);

        assertThat(patch).contains("+two").contains("NEW.txt").contains("+brand new");
        // Taken, not consumed: the working tree still has the change, and a second look
        // reports the same patch — the merge step may be prepared more than once.
        assertThat(workspace.patchOf(dir)).isEqualTo(patch);
    }
}
