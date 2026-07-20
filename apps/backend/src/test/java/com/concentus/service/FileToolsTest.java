package com.concentus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * File tools for the api backend, and the containment around them.
 *
 * <p>These give a model write access to disk, so the escape routes matter more than the happy
 * path: traversal, symlinks, absolute paths outside the workspace, and creating a file in a
 * directory that was never granted.
 */
class FileToolsTest {

    private final FileTools tools = new FileTools(new ObjectMapper());

    private static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":\"")
                    .append(kv[i + 1].replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n"))
                    .append('"');
        }
        return sb.append('}').toString();
    }

    // ------------------------------------------------------------------ containment

    @Test
    void readingOutsideTheWorkspaceIsRefused(@TempDir Path work, @TempDir Path elsewhere) throws IOException {
        Path secret = Files.writeString(elsewhere.resolve("secret.txt"), "top secret");

        String out = tools.execute(FileTools.READ, json("path", secret.toString()), List.of(work));

        assertThat(out).startsWith("Error:").contains("outside this agent's context folders");
        assertThat(out).doesNotContain("top secret");
    }

    @Test
    void traversalOutOfTheWorkspaceIsRefused(@TempDir Path parent) throws IOException {
        Path work = Files.createDirectory(parent.resolve("work"));
        Files.writeString(parent.resolve("sibling.txt"), "not yours");

        String out = tools.execute(FileTools.READ,
                json("path", work.resolve("..").resolve("sibling.txt").toString()), List.of(work));

        assertThat(out).startsWith("Error:");
        assertThat(out).doesNotContain("not yours");
    }

    @Test
    void aSymlinkPointingOutOfTheWorkspaceIsRefused(@TempDir Path work, @TempDir Path elsewhere)
            throws IOException {
        Files.writeString(elsewhere.resolve("secret.txt"), "top secret");
        Path link = work.resolve("escape.txt");
        try {
            Files.createSymbolicLink(link, elsewhere.resolve("secret.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeThat(false).as("symlink creation not permitted here").isTrue();
        }

        String out = tools.execute(FileTools.READ, json("path", link.toString()), List.of(work));

        // Containment is checked on the real path, so a link inside the workspace can't smuggle
        // in whatever it points at.
        assertThat(out).doesNotContain("top secret");
        assertThat(out).startsWith("Error:");
    }

    @Test
    void writingOutsideTheWorkspaceIsRefused(@TempDir Path work, @TempDir Path elsewhere) {
        String out = tools.execute(FileTools.WRITE,
                json("path", elsewhere.resolve("pwned.txt").toString(), "content", "x"), List.of(work));

        assertThat(out).startsWith("Error:");
        assertThat(elsewhere.resolve("pwned.txt")).doesNotExist();
    }

    @Test
    void creatingANewFileInsideTheWorkspaceIsAllowed(@TempDir Path work) {
        // A file that doesn't exist yet has no real path, so containment falls back to its
        // nearest existing ancestor — this must still work.
        Path target = work.resolve("nested").resolve("new.txt");

        String out = tools.execute(FileTools.WRITE,
                json("path", target.toString(), "content", "hello"), List.of(work));

        assertThat(out).doesNotStartWith("Error:");
        assertThat(target).exists().hasContent("hello");
    }

    @Test
    void anAgentWithNoContextFoldersGetsNoFileToolsAtAll() {
        // Better than offering tools that always refuse — the model never sees a capability it
        // can't use.
        assertThat(tools.toolsFor(List.of())).isEmpty();
        assertThat(tools.toolsFor(null)).isEmpty();
    }

    // ------------------------------------------------------------------ behaviour

    @Test
    void readsAFileInsideTheWorkspace(@TempDir Path work) throws IOException {
        Files.writeString(work.resolve("a.txt"), "contents here");

        String out = tools.execute(FileTools.READ,
                json("path", work.resolve("a.txt").toString()), List.of(work));

        assertThat(out).isEqualTo("contents here");
    }

    @Test
    void editReplacesAUniqueOccurrence(@TempDir Path work) throws IOException {
        Path file = Files.writeString(work.resolve("a.java"), "int x = 1;\nint y = 2;\n");

        String out = tools.execute(FileTools.EDIT,
                json("path", file.toString(), "old_text", "int x = 1;", "new_text", "int x = 42;"),
                List.of(work));

        assertThat(out).startsWith("Edited");
        assertThat(file).hasContent("int x = 42;\nint y = 2;\n");
    }

    @Test
    void editRefusesWhenTheTextAppearsTwice(@TempDir Path work) throws IOException {
        Path file = Files.writeString(work.resolve("a.java"), "foo();\nfoo();\n");

        String out = tools.execute(FileTools.EDIT,
                json("path", file.toString(), "old_text", "foo();", "new_text", "bar();"),
                List.of(work));

        // Picking one silently would be a corruption the model has no way to notice.
        assertThat(out).contains("more than once");
        assertThat(file).hasContent("foo();\nfoo();\n");
    }

    @Test
    void editRefusesWhenTheTextIsAbsent(@TempDir Path work) throws IOException {
        Path file = Files.writeString(work.resolve("a.java"), "foo();\n");

        String out = tools.execute(FileTools.EDIT,
                json("path", file.toString(), "old_text", "nope", "new_text", "bar();"),
                List.of(work));

        assertThat(out).contains("does not appear");
        assertThat(file).hasContent("foo();\n");
    }

    @Test
    void listSkipsNoiseDirectories(@TempDir Path work) throws IOException {
        Files.writeString(work.resolve("real.txt"), "x");
        Path git = Files.createDirectories(work.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref");
        Path modules = Files.createDirectories(work.resolve("node_modules").resolve("pkg"));
        Files.writeString(modules.resolve("index.js"), "x");

        String out = tools.execute(FileTools.LIST, json("path", work.toString()), List.of(work));

        assertThat(out).contains("real.txt");
        assertThat(out).doesNotContain("HEAD").doesNotContain("index.js");
    }

    @Test
    void aBadPathIsReportedToTheModelRatherThanThrown(@TempDir Path work) {
        String out = tools.execute(FileTools.READ,
                json("path", work.resolve("missing.txt").toString()), List.of(work));

        // The model should be able to correct itself; an exception would end the run.
        assertThat(out).startsWith("Error:").contains("no such file");
    }

    @Test
    void malformedArgumentsAreReportedNotThrown(@TempDir Path work) {
        assertThat(tools.execute(FileTools.READ, "not json", List.of(work)))
                .contains("not valid JSON");
    }
}
