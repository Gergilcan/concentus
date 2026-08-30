package com.concentus.web;

import com.concentus.config.AgentSpec;
import com.concentus.model.FacadeProfile;
import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
import com.concentus.service.RunService;
import com.concentus.store.FacadeProfileStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The planning endpoint: the token/mode gate, and the accept/reject loop on {@code plan_submit}.
 * The one design property worth pinning hard: this endpoint serves NOTHING but plan_submit —
 * a planner must not reach the flow's API operations by "just planning".
 */
class PlanToolsControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentRun run;
    private final FacadeProfileStore profiles = mock(FacadeProfileStore.class);

    private PlanToolsController controller(boolean fanout) {
        run = new AgentRun("run-1", "flow-1", "Flow");
        run.toolToken = "tok-plan";
        AgentSpec coord = new AgentSpec();
        coord.name = "Coordinator";
        coord.execution = fanout ? "fanout" : "";
        run.compiled = new CompiledFlow(coord, List.of());

        RunService runs = mock(RunService.class);
        when(runs.get(anyString())).thenReturn(Optional.of(run));
        when(profiles.list()).thenReturn(List.of(
                new FacadeProfile("fprof_1", "reader", "", List.of(), true, null)));
        return new PlanToolsController(runs, MAPPER, profiles, 8);
    }

    private static JsonNode rpc(String method, String params) {
        try {
            return MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
                    + (params == null ? "" : ",\"params\":" + params) + "}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String submit(String itemsJson) {
        return "{\"name\":\"plan_submit\",\"arguments\":{\"goal\":\"g\",\"items\":" + itemsJson + "}}";
    }

    @Test
    void theGateIsTokenAndFanoutModeTogether() {
        PlanToolsController c = controller(true);
        assertThat(c.rpc("run-1", "wrong", rpc("tools/list", null)).getStatusCode().value())
                .isEqualTo(401);

        // Right token, but the flow is not a fan-out: planning does not exist for it.
        PlanToolsController sub = controller(false);
        assertThat(sub.rpc("run-1", "tok-plan", rpc("tools/list", null)).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void theOnlyToolHereIsPlanSubmit() {
        JsonNode tools = controller(true).rpc("run-1", "tok-plan", rpc("tools/list", null))
                .getBody().path("result").path("tools");

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("plan_submit");
    }

    @Test
    void aSoundPlanIsAcceptedAndProfileNamesBecomeIds() {
        PlanToolsController c = controller(true);

        JsonNode result = c.rpc("run-1", "tok-plan", rpc("tools/call", submit(
                "[{\"id\":\"w1\",\"prompt\":\"do it\",\"profile\":\"Reader\"}]")))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(run.submittedPlan).isNotNull();
        assertThat(run.submittedPlan.items().get(0).profileId()).isEqualTo("fprof_1");
    }

    @Test
    void aBrokenPlanComesBackWithEveryProblemAtOnceAndIsNotStored() {
        PlanToolsController c = controller(true);

        JsonNode result = c.rpc("run-1", "tok-plan", rpc("tools/call", submit(
                "[{\"id\":\"w1\",\"prompt\":\"\",\"files\":[\"f\"]},"
                        + "{\"id\":\"w1\",\"prompt\":\"x\"},"
                        + "{\"id\":\"w2\",\"prompt\":\"y\",\"files\":[\"f\"]}]")))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).contains("no prompt").contains("more than once").contains("same file");
        assertThat(run.submittedPlan).isNull();
    }

    @Test
    void anUnknownProfileNameIsRejectedNamingTheAvailableOnes() {
        PlanToolsController c = controller(true);

        JsonNode result = c.rpc("run-1", "tok-plan", rpc("tools/call", submit(
                "[{\"id\":\"w1\",\"prompt\":\"x\",\"profile\":\"nope\"}]")))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText())
                .contains("'nope'").contains("reader");
        assertThat(run.submittedPlan).isNull();
    }

    @Test
    void aCorrectedResubmissionReplacesNothingItJustWins() {
        PlanToolsController c = controller(true);
        c.rpc("run-1", "tok-plan", rpc("tools/call", submit("[{\"id\":\"a\",\"prompt\":\"x\"}]")));
        c.rpc("run-1", "tok-plan", rpc("tools/call", submit(
                "[{\"id\":\"a\",\"prompt\":\"x\"},{\"id\":\"b\",\"prompt\":\"y\"}]")));

        assertThat(run.submittedPlan.items()).hasSize(2);
    }
}
