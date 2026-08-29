package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.LibraryAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AgentLibraryStore}: database-backed CRUD for reusable agent definitions,
 * the one-time import of the YAML files earlier builds kept on disk, and the seeding of the
 * bundled examples (src/main/resources/library-agents) into an empty library.
 */
class AgentLibraryStoreTest {

    private AgentLibraryStore store(Path dir) {
        TestDatabase.reset(TestDatabase.jdbc());
        AgentLibraryStore store =
                new AgentLibraryStore(TestDatabase.jdbc(), dir.toString(), dir.toString(), new ObjectMapper(), new OrgContext("default"));
        store.init();
        return store;
    }

    @Test
    void anEmptyLibraryIsSeededWithTheBundledExampleAgents(@TempDir Path dir) {
        List<String> ids = store(dir).list().stream().map(LibraryAgent::id).toList();

        // The four curated examples bundled under src/main/resources/library-agents.
        assertThat(ids).containsExactlyInAnyOrder(
                "backend-engineer", "code-reviewer", "frontend-engineer", "tech-lead");
    }

    @Test
    void yamlFilesFromAnOlderBuildAreImportedInsteadOfSeedingExamples(@TempDir Path dir) throws IOException {
        // What a file-backed install looks like when this build first opens it.
        Files.writeString(dir.resolve("custom.yaml"), "name: Custom\n");

        AgentLibraryStore store = store(dir);

        // Imported, and the examples are not added on top — an existing library is not empty.
        assertThat(store.list()).extracting(LibraryAgent::id).containsExactly("custom");
        assertThat(store.list()).extracting(LibraryAgent::name).containsExactly("Custom");
    }

    @Test
    void theImportedFilesAreSetAsideSoASecondStartDoesNotResurrectThem(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("custom.yaml"), "name: Custom\n");
        AgentLibraryStore first = store(dir);
        first.delete("custom");

        // A second start against the same directory must not bring the deleted agent back.
        AgentLibraryStore second =
                new AgentLibraryStore(TestDatabase.jdbc(), dir.toString(), dir.toString(), new ObjectMapper(), new OrgContext("default"));
        second.init();

        assertThat(second.list()).extracting(LibraryAgent::id).doesNotContain("custom");
    }

    @Test
    void savingWithNoIdAssignsOneAndRoundTrips(@TempDir Path dir) {
        AgentLibraryStore store = store(dir);

        LibraryAgent saved = store.save(new LibraryAgent(null, "My Agent", "claude-x", "medium", 4000, "Be terse."));

        assertThat(saved.id()).startsWith("agent_");
        LibraryAgent reloaded = store.get(saved.id()).orElseThrow();
        assertThat(reloaded.name()).isEqualTo("My Agent");
        assertThat(reloaded.model()).isEqualTo("claude-x");
        assertThat(reloaded.effort()).isEqualTo("medium");
        assertThat(reloaded.maxTokens()).isEqualTo(4000);
        assertThat(reloaded.systemPrompt()).isEqualTo("Be terse.");
    }

    @Test
    void savingWithAllOptionalFieldsNullAppliesModelDefaults(@TempDir Path dir) {
        AgentLibraryStore store = store(dir);

        LibraryAgent saved = store.save(new LibraryAgent(null, "My Agent", null, null, 0, null));

        assertThat(saved.model()).isEqualTo("claude-opus-4-8");
        assertThat(saved.effort()).isEqualTo("high");
        assertThat(saved.maxTokens()).isEqualTo(16000);
        // Normalised before storing, so the returned record and a reloaded one agree — which the
        // file-backed version did not manage: it returned the raw null and read back "".
        assertThat(saved.systemPrompt()).isEqualTo("");
        assertThat(store.get(saved.id()).orElseThrow().systemPrompt()).isEqualTo("");
    }

    @Test
    void savingWithAnExplicitIdUpdatesInPlace(@TempDir Path dir) {
        AgentLibraryStore store = store(dir);
        LibraryAgent saved = store.save(new LibraryAgent(null, "Original", "claude-x", "high", 1000, ""));

        store.save(new LibraryAgent(saved.id(), "Renamed", "claude-x", "high", 1000, ""));

        assertThat(store.list().stream().filter(a -> a.id().equals(saved.id())).count()).isEqualTo(1);
        assertThat(store.get(saved.id()).orElseThrow().name()).isEqualTo("Renamed");
    }

    @Test
    void deleteRemovesTheAgentAndIsIdempotent(@TempDir Path dir) {
        AgentLibraryStore store = store(dir);
        LibraryAgent saved = store.save(new LibraryAgent(null, "Temp", "claude-x", "high", 1000, ""));

        assertThat(store.delete(saved.id())).isTrue();
        assertThat(store.get(saved.id())).isEmpty();
        assertThat(store.delete(saved.id())).isFalse();
    }

    @Test
    void anUnreadableYamlFileIsSkippedWithoutLosingTheOthers(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("good.yaml"), "name: Good Agent\n");
        Files.writeString(dir.resolve("bad.yaml"), "not: [ valid: yaml structure for an AgentSpec");

        assertThat(store(dir).list()).extracting(LibraryAgent::name).containsExactly("Good Agent");
    }

    @Test
    void invalidIdIsRejectedOnDelete(@TempDir Path dir) {
        AgentLibraryStore store = store(dir);

        assertThatThrownBy(() -> store.delete("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- versions

    @Test
    void everySaveOfAnExistingAgentIsANewVersionAndTheClientsNumberIsNotTheCounter(@TempDir Path dir) {
        AgentLibraryStore store = store(dir);

        LibraryAgent first = store.save(new LibraryAgent(null, "Reviewer", "claude-x", "high", 1000, ""));
        assertThat(first.version()).isEqualTo(1);

        LibraryAgent second = store.save(
                new LibraryAgent(first.id(), "Reviewer", "claude-x", "high", 1000, "Be strict."));
        assertThat(second.version()).isEqualTo(2);

        // A stale form sending "version 1" again still lands on 3: the store is the only one
        // counting, otherwise two people editing the same agent could both produce a "v2".
        LibraryAgent third = store.save(
                new LibraryAgent(first.id(), "Reviewer", "claude-x", "high", 1000, "Be stricter.", "", 1));
        assertThat(third.version()).isEqualTo(3);
        assertThat(store.get(first.id()).orElseThrow().version()).isEqualTo(3);
        assertThat(store.list()).filteredOn(a -> a.id().equals(first.id()))
                .extracting(LibraryAgent::version).containsExactly(3L);
    }

    @Test
    void aRowWrittenBeforeVersionsExistedReadsAsVersionOne(@TempDir Path dir) {
        AgentLibraryStore store = store(dir);
        // What a row from an older build looks like: no version, no description.
        TestDatabase.jdbc().update(
                "insert into resources (kind, id, sort_key, json, updated_at, organization_id) values ('agent', 'old', 'Old', ?, 0, 'default')",
                "{\"id\":\"old\",\"name\":\"Old\",\"model\":\"claude-x\",\"effort\":\"high\","
                        + "\"maxTokens\":1000,\"systemPrompt\":\"\"}");

        LibraryAgent old = store.get("old").orElseThrow();

        // A block that linked it before versions existed carries no stamp either (0), so the
        // doctor's "behind" comparison must see the first version, not a zero it would call stale.
        assertThat(old.version()).isEqualTo(1);
        assertThat(old.description()).isEqualTo("");
        // And its first edit is the second version, as it would be for any other agent.
        assertThat(store.save(old).version()).isEqualTo(2);
    }

    @Test
    void aBackupRestoredIntoAnEmptyLibraryKeepsTheVersionsItsFlowsWereStampedWith(@TempDir Path dir) {
        AgentLibraryStore store = store(dir);

        LibraryAgent restored = store.save(
                new LibraryAgent("agent_restored", "Restored", "claude-x", "high", 1000, "", "", 7));

        // An unknown id keeps the number it came with: the flows in the same bundle link v7, and
        // resetting it to 1 would leave them "ahead" of a library that has not changed at all.
        assertThat(restored.version()).isEqualTo(7);
        assertThat(store.get("agent_restored").orElseThrow().version()).isEqualTo(7);
    }

    @Test
    void descriptionRoundTripsAndNullReadsBackEmpty(@TempDir Path dir) {
        AgentLibraryStore store = store(dir);

        LibraryAgent saved = store.save(
                new LibraryAgent(null, "Reviewer", "claude-x", "high", 1000, "", "Use for reviews.", 0));
        assertThat(store.get(saved.id()).orElseThrow().description()).isEqualTo("Use for reviews.");

        LibraryAgent bare = store.save(
                new LibraryAgent(null, "Bare", "claude-x", "high", 1000, "", null, 0));
        assertThat(bare.description()).isEqualTo("");
        assertThat(store.get(bare.id()).orElseThrow().description()).isEqualTo("");
    }
}
