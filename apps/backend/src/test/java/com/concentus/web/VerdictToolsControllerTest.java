package com.concentus.web;

import com.concentus.config.AgentSpec;
import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
import com.concentus.service.RunService;
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
 * The verdict endpoint: the token/mode gate, and the accept/reject loop on {@code verdict_submit}.
 * The design property worth pinning hard: this endpoint serves NOTHING but verdict_submit, and
 * its token is the verifier's own — the planner's token must not open it, or a planner could
 * pre-approve the outputs of the very workers it planned.
 */
class VerdictToolsControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentRun run;

    private VerdictToolsController controller(boolean fanout) {
        run = new AgentRun("run-1", "flow-1", "Flow");
        run.verdictToken = "tok-verdict";
        run.toolToken = "tok-plan";
        run.verdictExpected.add("n1");
        run.verdictExpected.add("n2");
        AgentSpec coord = new AgentSpec();
        coord.name = "Coordinator";
        coord.execution = fanout ? "fanout" : "";
        run.compiled = new CompiledFlow(coord, List.of());

        RunService runs = mock(RunService.class);
        when(runs.get(anyString())).thenReturn(Optional.of(run));
        return new VerdictToolsController(runs, MAPPER);
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
        return "{\"name\":\"verdict_submit\",\"arguments\":{\"summary\":\"s\",\"items\":" + itemsJson + "}}";
    }

    @Test
    void theGateIsTheVerifiersOwnTokenAndFanoutModeTogether() {
        VerdictToolsController c = controller(true);
        assertThat(c.rpc("run-1", "wrong", rpc("tools/list", null)).getStatusCode().value())
                .isEqualTo(401);
        // The planner's token opens the plan endpoint, never this one.
        assertThat(c.rpc("run-1", "tok-plan", rpc("tools/list", null)).getStatusCode().value())
                .isEqualTo(401);

        // Right token, but the flow is not a fan-out: there is nothing to verify.
        VerdictToolsController sub = controller(false);
        assertThat(sub.rpc("run-1", "tok-verdict", rpc("tools/list", null)).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void theOnlyToolHereIsVerdictSubmitAndItNamesTheWorkersAwaitingJudgment() {
        JsonNode tools = controller(true).rpc("run-1", "tok-verdict", rpc("tools/list", null))
                .getBody().path("result").path("tools");

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("verdict_submit");
        assertThat(tools.get(0).path("description").asText()).contains("n1").contains("n2");
    }

    @Test
    void aCompleteVerdictIsAcceptedAndStored() {
        VerdictToolsController c = controller(true);

        JsonNode result = c.rpc("run-1", "tok-verdict", rpc("tools/call", submit(
                "[{\"id\":\"n1\",\"verdict\":\"accept\"},"
                        + "{\"id\":\"n2\",\"verdict\":\"reject\",\"reason\":\"unverified claims\"}]")))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(run.submittedVerdict).isNotNull();
        assertThat(run.submittedVerdict.rejectedCount()).isEqualTo(1);
        // The console heard the judgment as it landed.
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("1 accepted, 1 rejected"));
    }

    @Test
    void anIncompleteVerdictComesBackWithEveryProblemAtOnceAndIsNotStored() {
        VerdictToolsController c = controller(true);

        JsonNode result = c.rpc("run-1", "tok-verdict", rpc("tools/call", submit(
                "[{\"id\":\"n1\",\"verdict\":\"reject\"}]")))
                .getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).contains("without a reason").contains("'n2' was not judged");
        assertThat(run.submittedVerdict).isNull();
    }

    @Test
    void aCorrectedResubmissionJustWins() {
        VerdictToolsController c = controller(true);
        c.rpc("run-1", "tok-verdict", rpc("tools/call", submit(
                "[{\"id\":\"n1\",\"verdict\":\"accept\"},{\"id\":\"n2\",\"verdict\":\"accept\"}]")));
        c.rpc("run-1", "tok-verdict", rpc("tools/call", submit(
                "[{\"id\":\"n1\",\"verdict\":\"reject\",\"reason\":\"stale\"},"
                        + "{\"id\":\"n2\",\"verdict\":\"accept\"}]")));

        assertThat(run.submittedVerdict.rejectedCount()).isEqualTo(1);
    }
}
