package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.mail.MailTriggerSpec;
import com.concentus.model.FlowGraph;
import com.concentus.model.TriggerSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Installing the bundled starter flows.
 *
 * <p>The reason this exists: a flow that lives only as a classpath resource is invisible. The
 * Flows page reads the store, so "the template is in the jar" and "the user has a flow" are
 * different statements, and only the second one is what was asked for.
 */
class FlowLibrarySeederTest {

    private static final String FLOW_ID = "outlook-quote-to-holded";

    private final ObjectMapper mapper = new ObjectMapper();

    private FlowStore seedInto(Path dataDir) {
        TestDatabase.reset(TestDatabase.jdbc());
        FlowStore flows = new FlowStore(TestDatabase.jdbc(), dataDir.toString(), mapper, new OrgContext("default"));
        flows.init();
        new FlowLibrarySeeder(TestDatabase.jdbc(), flows, mapper).seed();
        return flows;
    }

    @Test
    void theBundledFlowIsInstalledSoItAppearsInTheFlowList(@TempDir Path dataDir) {
        FlowStore flows = seedInto(dataDir);

        assertThat(flows.list()).extracting(FlowGraph::id).contains(FLOW_ID);
        assertThat(flows.get(FLOW_ID)).isPresent();
    }

    @Test
    void theInstalledFlowIsMailTriggeredOnAFolder(@TempDir Path dataDir) {
        FlowGraph flow = seedInto(dataDir).get(FLOW_ID).orElseThrow();

        assertThat(TriggerSpec.from(flow).mail()).isTrue();
        MailTriggerSpec mail = MailTriggerSpec.from(flow);
        assertThat(mail.folder()).isEqualTo("Presupuestos");
        // Unread-only, or the first poll of an existing folder would start a run per message
        // ever received.
        assertThat(mail.unseenOnly()).isTrue();
        assertThat(mail.movesProcessedMail()).isTrue();
    }

    @Test
    void theInstalledFlowCarriesNoCredentialOfAnyKind(@TempDir Path dataDir) {
        FlowGraph flow = seedInto(dataDir).get(FLOW_ID).orElseThrow();

        MailTriggerSpec mail = MailTriggerSpec.from(flow);
        // A committed file must never carry a secret. The node has no credential selected and no
        // host or username, so it is inert until an operator fills them in.
        assertThat(mail.credentialId()).isEmpty();
        assertThat(mail.host()).isEmpty();
        assertThat(mail.username()).isEmpty();
        assertThat(mail.isConfigured()).isFalse();
    }

    @Test
    void theInstalledFlowHasACoordinatorWithTheHoldedMcpAttached(@TempDir Path dataDir) {
        FlowGraph flow = seedInto(dataDir).get(FLOW_ID).orElseThrow();

        assertThat(flow.nodesOrEmpty()).anyMatch(n -> "agent".equals(n.type())
                && "coordinator".equals(n.role()));
        assertThat(flow.nodesOrEmpty()).filteredOn(n -> "mcp".equals(n.type()))
                .extracting(n -> n.dataOrEmpty().get("name"))
                .containsExactly("holded");
        // Wired to the agent, not floating: FlowCompiler attaches MCP servers through edges.
        assertThat(flow.edgesOrEmpty()).hasSize(2);
    }

    @Test
    void thePromptForbidsSendingAcceptingAndInvoicing(@TempDir Path dataDir) {
        FlowGraph flow = seedInto(dataDir).get(FLOW_ID).orElseThrow();

        String prompt = flow.nodesOrEmpty().stream()
                .filter(n -> "agent".equals(n.type()))
                .map(n -> String.valueOf(n.dataOrEmpty().get("systemPrompt")))
                .findFirst().orElseThrow();

        // These are the guarantees the whole design rests on; a prompt edit that loses them
        // should fail a test rather than quietly change what the agent may do.
        assertThat(prompt).contains("PROHIBICIONES ABSOLUTAS");
        assertThat(prompt).contains("aceptar un presupuesto");
        assertThat(prompt).contains("enviarlo al cliente");
        assertThat(prompt).contains("convertirlo en factura");
        assertThat(prompt).contains("eliminar");
        // Prompt-injection defence and idempotency.
        assertThat(prompt).contains("ENTRADA HOSTIL");
        assertThat(prompt).contains("IDEMPOTENCIA");
        assertThat(prompt).contains("NO INVENTES");
    }

    @Test
    void seedingTwiceDoesNotDuplicateOrOverwrite(@TempDir Path dataDir) {
        FlowStore flows = seedInto(dataDir);
        FlowGraph edited = flows.get(FLOW_ID).orElseThrow();
        flows.save(new FlowGraph(edited.id(), "My edited name", edited.mode(), edited.nodes(),
                edited.edges(), edited.enabled(), edited.tags(), edited.favorite(),
                edited.notifyWebhook()));

        new FlowLibrarySeeder(TestDatabase.jdbc(), flows, mapper).seed();

        // The user's edits survive a restart; re-seeding must never clobber them.
        assertThat(flows.get(FLOW_ID)).get().extracting(FlowGraph::name).isEqualTo("My edited name");
        assertThat(flows.list()).filteredOn(f -> FLOW_ID.equals(f.id())).hasSize(1);
    }

    @Test
    void aDeletedStarterFlowStaysDeleted(@TempDir Path dataDir) {
        FlowStore flows = seedInto(dataDir);
        flows.delete(FLOW_ID);

        new FlowLibrarySeeder(TestDatabase.jdbc(), flows, mapper).seed();

        // It is a starter, not something the app keeps reinstating against the user's wishes.
        assertThat(flows.get(FLOW_ID)).isEmpty();
    }

    @Test
    void anExistingUnrelatedFlowDoesNotBlockSeeding(@TempDir Path dataDir) {
        // The naive "seed only when the directory is empty" rule would skip the starter for every
        // install that already had a flow — which is all of them.
        TestDatabase.reset(TestDatabase.jdbc());
        FlowStore flows = new FlowStore(TestDatabase.jdbc(), dataDir.toString(), mapper, new OrgContext("default"));
        flows.init();
        flows.save(new FlowGraph("existing", "Something else", "local", java.util.List.of(),
                java.util.List.of(), true, java.util.List.of(), false, null));

        new FlowLibrarySeeder(TestDatabase.jdbc(), flows, mapper).seed();

        assertThat(flows.get(FLOW_ID)).isPresent();
        assertThat(flows.get("existing")).isPresent();
    }

    @Test
    void theFlowIsEnabledSoItIsLiveOnceItsSecretIsSet(@TempDir Path dataDir) {
        Optional<FlowGraph> flow = seedInto(dataDir).get(FLOW_ID);

        assertThat(flow).get().extracting(FlowGraph::enabledOrDefault).isEqualTo(true);
    }
}
