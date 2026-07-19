package com.concentus.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The allowlist guarding which host folders a flow may read.
 *
 * <p>Flows are editable over HTTP and can be fired by a public webhook, so this is the boundary
 * between "an agent reads my project" and "an agent reads my whole filesystem". Every escape route
 * — traversal, symlinks, an unconfigured allowlist — is pinned down here.
 */
class ContextFolderResolverTest {

    private final List<String> rejections = new ArrayList<>();

    private ContextFolderResolver resolver(Path... roots) {
        String joined = String.join(",", java.util.Arrays.stream(roots).map(Path::toString).toList());
        return new ContextFolderResolver(joined);
    }

    private List<Path> resolve(ContextFolderResolver r, String... paths) {
        return r.resolve(List.of(paths), (p, reason) -> rejections.add(p + ": " + reason));
    }

    @Test
    void withNoRootsConfiguredNothingIsAllowed(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectory(tmp.resolve("project"));
        ContextFolderResolver r = new ContextFolderResolver("");

        assertThat(resolve(r, project.toString())).isEmpty();
        assertThat(r.configured()).isFalse();
        // The message has to say what to do, or an empty context looks like a silent bug.
        assertThat(rejections).singleElement().asString().contains("local.context-roots");
    }

    @Test
    void aFolderInsideAConfiguredRootIsAllowed(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectory(tmp.resolve("wirej"));
        ContextFolderResolver r = resolver(tmp);

        assertThat(resolve(r, project.toString())).containsExactly(project.toRealPath());
        assertThat(rejections).isEmpty();
    }

    @Test
    void aFolderOutsideEveryRootIsRejected(@TempDir Path tmp, @TempDir Path other) throws IOException {
        Files.createDirectory(tmp.resolve("allowed"));
        Path outside = Files.createDirectory(other.resolve("secrets"));
        ContextFolderResolver r = resolver(tmp);

        assertThat(resolve(r, outside.toString())).isEmpty();
        assertThat(rejections).singleElement().asString().contains("outside the configured context roots");
    }

    @Test
    void traversalOutOfARootIsRejected(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("root"));
        Files.createDirectory(tmp.resolve("sibling"));
        ContextFolderResolver r = resolver(root);

        // Normalised before comparison, so ".." cannot walk out of an allowed root.
        assertThat(resolve(r, root.resolve("..").resolve("sibling").toString())).isEmpty();
        assertThat(rejections).isNotEmpty();
    }

    @Test
    void aSymlinkPointingOutOfARootIsRejected(@TempDir Path tmp, @TempDir Path other) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("root"));
        Path secret = Files.createDirectory(other.resolve("secret"));
        Path link = root.resolve("escape");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            assumeThat(false).as("symlink creation not permitted on this machine").isTrue();
        }
        ContextFolderResolver r = resolver(root);

        // Containment is checked on the real path — a link inside a root must not smuggle in
        // whatever it points at.
        assertThat(resolve(r, link.toString())).isEmpty();
        assertThat(rejections).isNotEmpty();
    }

    @Test
    void aFileOrMissingPathIsNotAContextFolder(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("notes.txt"), "x");
        ContextFolderResolver r = resolver(tmp);

        assertThat(resolve(r, file.toString(), tmp.resolve("nope").toString())).isEmpty();
        assertThat(rejections).hasSize(2)
                .allSatisfy(m -> assertThat(m).contains("not an existing directory"));
    }

    @Test
    void duplicatesCollapseSoAddDirIsNotRepeated(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectory(tmp.resolve("p"));
        ContextFolderResolver r = resolver(tmp);

        assertThat(resolve(r, project.toString(), project.toString())).hasSize(1);
    }

    @Test
    void oneBadFolderDoesNotDiscardTheGoodOnes(@TempDir Path tmp, @TempDir Path other) throws IOException {
        Path good = Files.createDirectory(tmp.resolve("good"));
        Path bad = Files.createDirectory(other.resolve("bad"));
        ContextFolderResolver r = resolver(tmp);

        // A half-configured flow still runs, with the reason visible in the console.
        assertThat(resolve(r, good.toString(), bad.toString())).containsExactly(good.toRealPath());
        assertThat(rejections).hasSize(1);
    }

    @Test
    void multipleRootsAreEachHonoured(@TempDir Path a, @TempDir Path b) throws IOException {
        Path one = Files.createDirectory(a.resolve("one"));
        Path two = Files.createDirectory(b.resolve("two"));
        ContextFolderResolver r = resolver(a, b);

        assertThat(resolve(r, one.toString(), two.toString()))
                .containsExactly(one.toRealPath(), two.toRealPath());
    }

    // ---- CLAUDE.md reference ----

    @Test
    void claudeMdIsFoundFromAFolderReference(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectory(tmp.resolve("wirej"));
        Path md = Files.writeString(project.resolve("CLAUDE.md"), "# WireJ");
        ContextFolderResolver r = resolver(tmp);

        Path found = r.resolveClaudeMd(project.toString(), (p, reason) -> rejections.add(reason));
        assertThat(found).isEqualTo(md);
        assertThat(rejections).isEmpty();
    }

    @Test
    void claudeMdIsAcceptedAsADirectFilePath(@TempDir Path tmp) throws IOException {
        Path md = Files.writeString(tmp.resolve("CLAUDE.md"), "# root");
        ContextFolderResolver r = resolver(tmp);

        assertThat(r.resolveClaudeMd(md.toString(), (p, reason) -> rejections.add(reason))).isEqualTo(md);
    }

    @Test
    void claudeMdOutsideTheRootsIsRejected(@TempDir Path tmp, @TempDir Path other) throws IOException {
        Files.createDirectory(tmp.resolve("allowed"));
        Path md = Files.writeString(other.resolve("CLAUDE.md"), "# elsewhere");
        ContextFolderResolver r = resolver(tmp);

        assertThat(r.resolveClaudeMd(md.toString(), (p, reason) -> rejections.add(reason))).isNull();
        assertThat(rejections).singleElement().asString().contains("outside the configured context roots");
    }

    @Test
    void aFolderWithNoClaudeMdReportsWhy(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectory(tmp.resolve("empty"));
        ContextFolderResolver r = resolver(tmp);

        assertThat(r.resolveClaudeMd(project.toString(), (p, reason) -> rejections.add(reason))).isNull();
        assertThat(rejections).singleElement().asString().contains("no CLAUDE.md found there");
    }

    @Test
    void aBlankReferenceIsSimplyUnset(@TempDir Path tmp) {
        ContextFolderResolver r = resolver(tmp);

        // Not configuring one is normal, not an error worth reporting.
        assertThat(r.resolveClaudeMd("", (p, reason) -> rejections.add(reason))).isNull();
        assertThat(r.resolveClaudeMd(null, (p, reason) -> rejections.add(reason))).isNull();
        assertThat(rejections).isEmpty();
    }
}
