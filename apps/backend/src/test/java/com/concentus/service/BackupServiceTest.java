package com.concentus.service;

import com.concentus.auth.OrgContext;
import com.concentus.model.FlowGraph;
import com.concentus.model.McpDef;
import com.concentus.model.SkillDef;
import com.concentus.model.Variable;
import com.concentus.secrets.CredentialStore;
import com.concentus.store.AgentLibraryStore;
import com.concentus.store.DatabaseStore;
import com.concentus.store.FacadeProfileStore;
import com.concentus.store.FlowStore;
import com.concentus.store.KnowledgeStore;
import com.concentus.store.McpDefStore;
import com.concentus.store.SkillStore;
import com.concentus.store.VariableStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * The one-file contract: what the export carries (and pointedly does not), and that an import
 * lands every record under its ORIGINAL id — cross-references travel by id, and losing them
 * would import a library of flows pointing at nothing.
 */
class BackupServiceTest {

    private final FlowStore flows = mock(FlowStore.class);
    private final AgentLibraryStore agents = mock(AgentLibraryStore.class);
    private final McpDefStore mcpDefs = mock(McpDefStore.class);
    private final FacadeProfileStore facades = mock(FacadeProfileStore.class);
    private final DatabaseStore databases = mock(DatabaseStore.class);
    private final KnowledgeStore knowledge = mock(KnowledgeStore.class);
    private final SkillStore skills = mock(SkillStore.class);
    private final VariableStore variables = mock(VariableStore.class);
    private final CredentialStore credentials = mock(CredentialStore.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final BackupService service = new BackupService(flows, agents, mcpDefs, facades,
            databases, knowledge, skills, variables, credentials, new OrgContext("local"),
            mapper);

    private void emptyStores() {
        when(flows.list()).thenReturn(List.of());
        when(agents.list()).thenReturn(List.of());
        when(mcpDefs.list()).thenReturn(List.of());
        when(facades.list()).thenReturn(List.of());
        when(databases.list()).thenReturn(List.of());
        when(knowledge.list()).thenReturn(List.of());
        when(skills.list()).thenReturn(List.of());
        when(variables.list()).thenReturn(List.of());
        when(credentials.isAvailable()).thenReturn(true);
        when(credentials.list(anyString())).thenReturn(List.of());
    }

    @Test
    void theExportCarriesEveryCategoryAndByDefaultNeverASecret() {
        emptyStores();
        when(mcpDefs.list()).thenReturn(List.of(McpDef.http("mcp_1", "Linear", "https://x", "cred_9", null)));
        when(credentials.list("local")).thenReturn(List.of(new CredentialStore.Credential(
                "cred_9", "local", "Linear token", "api-token", "ab…yz", 1L, 1L, null, false)));
        when(credentials.revealForExport("local", "cred_9")).thenReturn(Optional.of("lin_api_secret"));

        JsonNode doc = service.export(false);

        assertThat(doc.path("concentusExport").asInt()).isEqualTo(1);
        assertThat(doc.path("includesSecrets").asBoolean()).isFalse();
        for (String key : List.of("flows", "agents", "mcpServers", "facadeProfiles", "databases",
                "knowledgeBases", "skills", "variables", "credentials")) {
            assertThat(doc.has(key)).as(key).isTrue();
        }
        JsonNode cred = doc.path("credentials").get(0);
        assertThat(cred.path("id").asText()).isEqualTo("cred_9");
        assertThat(cred.path("label").asText()).isEqualTo("Linear token");
        // Metadata only: no secret, not even the hint, appears anywhere in the document — and the
        // value is never even asked for.
        assertThat(doc.toString()).doesNotContain("ab…yz").doesNotContain("secret");
        verify(credentials, never()).revealForExport(anyString(), anyString());
    }

    /**
     * The export an administrator asked for explicitly: values in the clear for what this
     * installation can open, and an honest {@code locked} for what it cannot — written down rather
     * than omitted, so the person restoring the file knows before they need it.
     */
    @Test
    void anExportWithSecretsCarriesValuesAndMarksWhatIsLocked() {
        emptyStores();
        when(credentials.list("local")).thenReturn(List.of(
                new CredentialStore.Credential("cred_9", "local", "Linear token", "api-token",
                        "ab…yz", 1L, 1L, null, false),
                new CredentialStore.Credential("cred_locked", "local", "Google Ads", "api-token",
                        "cd…wx", 1L, 1L, null, true)));
        when(credentials.revealForExport("local", "cred_9")).thenReturn(Optional.of("lin_api_secret"));
        when(credentials.revealForExport("local", "cred_locked")).thenReturn(Optional.empty());

        JsonNode doc = service.export(true);

        assertThat(doc.path("includesSecrets").asBoolean()).isTrue();
        JsonNode open = doc.path("credentials").get(0);
        assertThat(open.path("secret").asText()).isEqualTo("lin_api_secret");
        assertThat(open.has("locked")).isFalse();
        JsonNode locked = doc.path("credentials").get(1);
        assertThat(locked.path("locked").asBoolean()).isTrue();
        assertThat(locked.has("secret")).isFalse();
        // An export is not a use: the path that moves last_used_at is never taken.
        verify(credentials, never()).reveal(anyString(), anyString());
    }

    // A with-secrets file restores values under their original ids; its locked entries arrive as
    // placeholders to re-enter, exactly as the plain export's do.
    @Test
    void importingAFileWithSecretsRestoresValuesAndPlaceholdersTheLockedOnes() throws Exception {
        emptyStores();
        when(credentials.importSecret(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CredentialStore.ImportOutcome.CREATED);
        when(credentials.importPlaceholder(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        BackupService.ImportReport report = service.importBundle(mapper.readTree("""
                {"concentusExport": 1, "includesSecrets": true,
                 "credentials": [
                   {"id": "cred_9", "label": "Linear token", "kind": "api-token", "secret": "lin_api_secret"},
                   {"id": "cred_locked", "label": "Google Ads", "kind": "api-token", "locked": true}]}"""));

        verify(credentials).importSecret("local", "cred_9", "Linear token", "api-token", "lin_api_secret");
        verify(credentials, never()).importPlaceholder(anyString(), eq("cred_9"), anyString(), anyString());
        verify(credentials).importPlaceholder("local", "cred_locked", "Google Ads", "api-token");
        assertThat(report.imported()).containsEntry("credentials", 2);
        assertThat(report.credentialsToReenter()).containsExactly("Google Ads");
    }

    @Test
    void importLandsRecordsUnderTheirOriginalIds() throws Exception {
        emptyStores();
        when(flows.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(variables.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(credentials.importPlaceholder(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        BackupService.ImportReport report = service.importBundle(mapper.readTree("""
                {"concentusExport": 1,
                 "flows": [{"id": "flow_7", "name": "Triage", "mode": "local", "nodes": [], "edges": []}],
                 "variables": [{"id": "var_3", "name": "COMPANY", "value": "ACME"}],
                 "credentials": [{"id": "cred_9", "label": "Linear token", "kind": "api-token"}]}"""));

        ArgumentCaptor<FlowGraph> savedFlow = ArgumentCaptor.forClass(FlowGraph.class);
        verify(flows).save(savedFlow.capture());
        assertThat(savedFlow.getValue().id()).isEqualTo("flow_7");
        ArgumentCaptor<Variable> savedVar = ArgumentCaptor.forClass(Variable.class);
        verify(variables).save(savedVar.capture());
        assertThat(savedVar.getValue().id()).isEqualTo("var_3");
        verify(credentials).importPlaceholder("local", "cred_9", "Linear token", "api-token");

        assertThat(report.imported()).containsEntry("flows", 1).containsEntry("variables", 1)
                .containsEntry("credentials", 1);
        assertThat(report.credentialsToReenter()).containsExactly("Linear token");
    }

    @Test
    void anExistingCredentialKeepsItsRealSecretAndIsNotListedForReentry() throws Exception {
        emptyStores();
        when(credentials.importPlaceholder(anyString(), eq("cred_9"), anyString(), anyString()))
                .thenReturn(false); // id already present: the machine's own secret wins

        BackupService.ImportReport report = service.importBundle(mapper.readTree("""
                {"concentusExport": 1,
                 "credentials": [{"id": "cred_9", "label": "Linear token", "kind": "api-token"}]}"""));

        assertThat(report.credentialsToReenter()).isEmpty();
        assertThat(report.imported()).containsEntry("credentials", 0);
    }

    @Test
    void aBrokenRecordIsSkippedWithAWarningAndTheRestStillLands(){
        emptyStores();
        when(skills.save(any())).thenAnswer(inv -> {
            SkillDef def = inv.getArgument(0);
            if ("broken".equals(def.name())) throw new IllegalStateException("no room");
            return def;
        });

        BackupService.ImportReport report = service.importBundle(mapper.valueToTree(Map.of(
                "concentusExport", 1,
                "skills", List.of(
                        new SkillDef("s1", "broken", "", List.of()),
                        new SkillDef("s2", "fine", "", List.of())))));

        assertThat(report.imported()).containsEntry("skills", 1);
        assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("skills"));
    }

    @Test
    void refusesFilesThatAreNotAnExportOrAreFromTheFuture() throws Exception {
        assertThatThrownBy(() -> service.importBundle(mapper.readTree("{\"random\": true}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.importBundle(
                mapper.readTree("{\"concentusExport\": 99}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newer");
    }
}
