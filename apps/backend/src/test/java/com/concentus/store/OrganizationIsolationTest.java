package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.config.SettingsStore;
import com.concentus.model.FacadeProfile;
import com.concentus.model.FlowGraph;
import com.concentus.model.KnowledgeDef;
import com.concentus.secrets.SecretCipher;
import com.concentus.service.AgentRun;
import com.concentus.service.RunDiffService;
import com.concentus.service.RunService;
import com.concentus.web.RunController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two organizations on one database: what A has, B cannot see, open, change or delete.
 *
 * <p>Each store is driven twice through the same table, once as a request from A and once as a
 * request from B, with the organization coming from the context the way it does in production —
 * never from a parameter the test could get right by accident.
 */
class OrganizationIsolationTest {

    private static final String A = "org_a";
    private static final String B = "org_b";

    @TempDir
    Path dataDir;

    private JdbcTemplate jdbc;

    /** An OrgContext for a signed-in member of one organization, without a security context. */
    private static OrgContext in(String organizationId) {
        return new OrgContext("default") {
            @Override
            public String currentOrganizationId() {
                return organizationId;
            }

            @Override
            public String requireOrganizationId() {
                return organizationId;
            }
        };
    }

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.jdbc();
        TestDatabase.reset(jdbc);
        jdbc.execute("delete from settings");
    }

    private FlowStore flowsIn(String organizationId) {
        FlowStore store = new FlowStore(jdbc, dataDir.toString(), new ObjectMapper(), in(organizationId));
        store.init();
        return store;
    }

    private static FlowGraph flow(String id, String name) {
        return new FlowGraph(id, name, List.of(), List.of(), null, List.of(), null, null);
    }

    // ---- resources (flows stand for every JsonStore kind) ----

    @Test
    void flows_of_one_organization_are_invisible_from_the_other() {
        FlowStore a = flowsIn(A);
        FlowStore b = flowsIn(B);
        FlowGraph ours = a.save(flow(null, "Ads"));
        b.save(flow(null, "Theirs"));

        assertThat(a.list()).extracting(FlowGraph::name).containsExactly("Ads");
        assertThat(b.list()).extracting(FlowGraph::name).containsExactly("Theirs");
        // By id, from the wrong organization: the same answer as for an id that does not exist,
        // which is what a controller turns into its 404.
        assertThat(b.get(ours.id())).isEmpty();
        assertThat(a.get(ours.id())).isPresent();
        assertThat(a.organizationOf(ours.id())).contains(A);
    }

    @Test
    void the_other_organization_cannot_delete_or_overwrite_by_id() {
        FlowStore a = flowsIn(A);
        FlowStore b = flowsIn(B);
        FlowGraph ours = a.save(flow(null, "Ads"));

        assertThat(b.delete(ours.id())).isFalse();
        assertThat(a.get(ours.id())).isPresent();

        // An upsert onto somebody else's id is refused rather than becoming theirs or ours.
        assertThatThrownBy(() -> b.save(flow(ours.id(), "Hijacked")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another organization");
        assertThat(a.get(ours.id()).orElseThrow().name()).isEqualTo("Ads");
        assertThat(b.list()).isEmpty();
    }

    @Test
    void the_schedulers_see_every_organization_and_a_trigger_finds_its_flow_by_id() {
        FlowStore a = flowsIn(A);
        FlowGraph ours = a.save(flow(null, "Ads"));
        flowsIn(B).save(flow(null, "Theirs"));

        assertThat(a.listAcrossOrganizations()).extracting(FlowGraph::name).containsExactly("Ads", "Theirs");
        assertThat(flowsIn(B).getAcrossOrganizations(ours.id())).isPresent();
        assertThat(flowsIn(B).getIn(A, ours.id())).isPresent();
        assertThat(flowsIn(B).getIn(B, ours.id())).isEmpty();
    }

    @Test
    void facades_and_knowledge_bases_are_partitioned_the_same_way() {
        FacadeProfileStore facadesA = new FacadeProfileStore(jdbc, new ObjectMapper(), in(A));
        FacadeProfileStore facadesB = new FacadeProfileStore(jdbc, new ObjectMapper(), in(B));
        facadesA.init();
        facadesB.init();
        FacadeProfile profile = new FacadeProfile(null, "Read only", "", List.of(), true, Boolean.FALSE);
        FacadeProfile saved = facadesA.save(profile);

        assertThat(facadesB.list()).isEmpty();
        assertThat(facadesB.get(saved.id())).isEmpty();
        assertThat(facadesA.get(saved.id())).isPresent();

        KnowledgeStore knowledgeA = new KnowledgeStore(jdbc, new ObjectMapper(), in(A));
        KnowledgeStore knowledgeB = new KnowledgeStore(jdbc, new ObjectMapper(), in(B));
        knowledgeA.init();
        knowledgeB.init();
        KnowledgeDef base = knowledgeA.save(new KnowledgeDef(null, "Manuals", null, null));

        assertThat(knowledgeB.list()).isEmpty();
        assertThat(knowledgeB.get(base.id())).isEmpty();
        assertThat(knowledgeA.list()).extracting(KnowledgeDef::name).containsExactly("Manuals");
    }

    // ---- settings: keyed by organization already, proven here across two. Credentials, the
    // same, in CredentialOrganizationIsolationTest beside the store's package-private init. ----

    @Test
    void settings_of_one_organization_do_not_leak_into_the_other() {
        SettingsStore settings = new SettingsStore(jdbc, new SecretCipher(""));
        settings.put(A, "runs.max-concurrent", "3", false, "admin@a");

        assertThat(settings.get(B, "runs.max-concurrent")).isEmpty();
        assertThat(settings.all(B)).isEmpty();
        assertThat(settings.get(A, "runs.max-concurrent")).contains("3");
    }

    // ---- runs: one registry for the deployment, partitioned at the controller ----

    @Test
    void the_run_list_and_a_run_by_id_answer_for_the_callers_organization_only() {
        AgentRun ours = new AgentRun("run_a", "flow_a", "Ads");
        ours.organizationId = A;
        AgentRun theirs = new AgentRun("run_b", "flow_b", "Theirs");
        theirs.organizationId = B;
        RunService runs = mock(RunService.class);
        when(runs.list()).thenReturn(List.of(ours.toSummary(), theirs.toSummary()));
        when(runs.get("run_a")).thenReturn(Optional.of(ours));
        when(runs.get("run_b")).thenReturn(Optional.of(theirs));
        RunController asB = new RunController(runs, mock(FlowStore.class), mock(RunDiffService.class), in(B));

        assertThat(asB.list()).extracting(s -> s.id()).containsExactly("run_b");
        assertThat(asB.get("run_b").run().id()).isEqualTo("run_b");
        assertThatThrownBy(() -> asB.get("run_a"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such run");
    }
}
