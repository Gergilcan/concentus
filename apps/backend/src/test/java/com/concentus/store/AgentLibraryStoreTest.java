package com.concentus.store;

import com.concentus.model.LibraryAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentLibraryStore}: YAML-file-backed CRUD for reusable agent definitions,
 * plus its one-time seeding of the bundled example agents (src/main/resources/library-agents)
 * into a fresh, empty library directory.
 */
class AgentLibraryStoreTest {

    @Test
    void firstRunOnAnEmptyDirSeedsTheBundledExampleAgents(@TempDir Path dir) throws IOException {
        AgentLibraryStore store = new AgentLibraryStore(dir.toString(), dir.toString());

        List<String> ids = store.list().stream().map(LibraryAgent::id).toList();

        // The four curated examples bundled under src/main/resources/library-agents.
        assertThat(ids).containsExactlyInAnyOrder(
                "backend-engineer", "code-reviewer", "frontend-engineer", "tech-lead");
    }

    @Test
    void seedingDoesNotOverwriteAnExistingNonEmptyDir(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("custom.yaml"), "name: Custom\n");

        AgentLibraryStore store = new AgentLibraryStore(dir.toString(), dir.toString());

        // Seeding is skipped entirely once the dir already has at least one yaml file.
        assertThat(store.list()).extracting(LibraryAgent::id).containsExactly("custom");
    }

    @Test
    void savingWithNoIdAssignsOneAndPersistsAsYaml(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".keep-nonempty.yaml"), "name: placeholder\n"); // skip seeding
        AgentLibraryStore store = new AgentLibraryStore(dir.toString(), dir.toString());

        LibraryAgent saved = store.save(new LibraryAgent(null, "My Agent", "claude-x", "medium", 4000, "Be terse."));

        assertThat(saved.id()).startsWith("agent_");
        assertThat(Files.exists(dir.resolve(saved.id() + ".yaml"))).isTrue();
        LibraryAgent reloaded = store.list().stream()
                .filter(a -> a.id().equals(saved.id())).findFirst().orElseThrow();
        assertThat(reloaded.name()).isEqualTo("My Agent");
        assertThat(reloaded.model()).isEqualTo("claude-x");
        assertThat(reloaded.effort()).isEqualTo("medium");
        assertThat(reloaded.maxTokens()).isEqualTo(4000);
        assertThat(reloaded.systemPrompt()).isEqualTo("Be terse.");
    }

    @Test
    void savingWithAllOptionalFieldsNullAppliesModelDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".keep-nonempty.yaml"), "name: placeholder\n"); // skip seeding
        AgentLibraryStore store = new AgentLibraryStore(dir.toString(), dir.toString());

        LibraryAgent saved = store.save(new LibraryAgent(null, "My Agent", null, null, 0, null));

        assertThat(saved.model()).isEqualTo("claude-opus-4-8");
        assertThat(saved.effort()).isEqualTo("high");
        assertThat(saved.maxTokens()).isEqualTo(16000);
        // save() normalizes a null systemPrompt to "" everywhere else it touches the value — the
        // YAML doc actually written to disk gets "" for a null input (see AgentLibraryStore.save,
        // `doc.put("systemPrompt", a.systemPrompt() == null ? "" : a.systemPrompt())`) — but the
        // record returned to the caller is built from the raw input (`a.systemPrompt()`), not the
        // normalized `doc` value, so it still carries null. Reloading via list() (which re-parses
        // the written YAML) correctly returns "".
        assertThat(saved.systemPrompt()).isEqualTo("");
    }

    @Test
    void savingWithAnExplicitIdUpdatesThatFileInPlace(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".keep-nonempty.yaml"), "name: placeholder\n");
        AgentLibraryStore store = new AgentLibraryStore(dir.toString(), dir.toString());
        LibraryAgent saved = store.save(new LibraryAgent(null, "Original", "claude-x", "high", 1000, ""));

        store.save(new LibraryAgent(saved.id(), "Renamed", "claude-x", "high", 1000, ""));

        long matching = store.list().stream().filter(a -> a.id().equals(saved.id())).count();
        assertThat(matching).isEqualTo(1);
        assertThat(store.list().stream().filter(a -> a.id().equals(saved.id())).findFirst().orElseThrow().name())
                .isEqualTo("Renamed");
    }

    @Test
    void deleteRemovesTheYamlFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".keep-nonempty.yaml"), "name: placeholder\n");
        AgentLibraryStore store = new AgentLibraryStore(dir.toString(), dir.toString());
        LibraryAgent saved = store.save(new LibraryAgent(null, "Temp", "claude-x", "high", 1000, ""));

        assertThat(store.delete(saved.id())).isTrue();
        assertThat(Files.exists(dir.resolve(saved.id() + ".yaml"))).isFalse();
        assertThat(store.delete(saved.id())).isFalse();
    }

    @Test
    void listSkipsAnUnreadableYamlFileButStillReturnsTheOthers(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("good.yaml"), "name: Good Agent\n");
        Files.writeString(dir.resolve("bad.yaml"), "not: [ valid: yaml structure for an AgentSpec");
        AgentLibraryStore store = new AgentLibraryStore(dir.toString(), dir.toString());

        List<String> names = store.list().stream().map(LibraryAgent::name).toList();
        assertThat(names).containsExactly("Good Agent");
    }

    @Test
    void invalidIdIsRejectedOnDelete(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".keep-nonempty.yaml"), "name: placeholder\n");
        AgentLibraryStore store = new AgentLibraryStore(dir.toString(), dir.toString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.delete("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
