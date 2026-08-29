package com.concentus.web;

import com.concentus.model.FlowEvalCase;
import com.concentus.model.FlowEvalResult;
import com.concentus.model.FlowGraph;
import com.concentus.service.EvalRunService;
import com.concentus.store.EvalDatasetStore;
import com.concentus.store.EvalResultStore;
import com.concentus.store.FlowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvalController}: what a case must carry to be saved, and that every case
 * and result is scoped to the flow in the URL. Hand-wired mocks — no Spring context, no database.
 */
class EvalControllerTest {

    private final FlowStore flows = mock(FlowStore.class);
    private final EvalDatasetStore dataset = mock(EvalDatasetStore.class);
    private final EvalResultStore results = mock(EvalResultStore.class);
    private final EvalRunService evaluations = mock(EvalRunService.class);
    private final EvalController controller = new EvalController(flows, dataset, results, evaluations);

    private static final FlowGraph FLOW = new FlowGraph("f1", "Flow", "local", List.of(), List.of(),
            null, List.of(), null, null);

    @BeforeEach
    void setUp() {
        when(flows.get("f1")).thenReturn(Optional.of(FLOW));
        when(dataset.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static FlowEvalCase aCase(String id, String flowId, String judge) {
        return new FlowEvalCase(id, flowId, "Case", "input", "expected", judge, 5L);
    }

    // ---------------------------------------------------------------- saving cases

    @Test
    void theFlowIdComesFromThePathNotTheBody() {
        // A body naming another flow must not be able to plant a case in it.
        FlowEvalCase saved = controller.save("f1", aCase(null, "f2", "contains"));

        assertThat(saved.flowId()).isEqualTo("f1");
        assertThat(saved.createdAt()).isPositive();
    }

    @Test
    void editingACaseKeepsWhenItWasCreated() {
        when(dataset.get("c1")).thenReturn(Optional.of(aCase("c1", "f1", "contains")));

        FlowEvalCase saved = controller.save("f1", new FlowEvalCase("c1", "f1", "Renamed", "in", "out", "regex", 0));

        // The order of the list is the order cases were written in; an edit must not move it.
        assertThat(saved.createdAt()).isEqualTo(5L);
        assertThat(saved.name()).isEqualTo("Renamed");
        assertThat(saved.judge()).isEqualTo("regex");
    }

    @Test
    void editingAnotherFlowsCaseThroughThisFlowIsNotFound() {
        when(dataset.get("c1")).thenReturn(Optional.of(aCase("c1", "f2", "contains")));

        assertThatThrownBy(() -> controller.save("f1", aCase("c1", "f1", "contains")))
                .isInstanceOf(ResponseStatusException.class);
        verify(dataset, never()).save(any());
    }

    @Test
    void aCaseNeedsAnExpectationAndAKnownJudge() {
        assertThatThrownBy(() -> controller.save("f1", new FlowEvalCase(null, "f1", "Case", "in", " ", "contains", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectation");
        assertThatThrownBy(() -> controller.save("f1", aCase(null, "f1", "vibes")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains, regex, exact, llm");
        assertThatThrownBy(() -> controller.save("f1", new FlowEvalCase(null, "f1", " ", "in", "out", "contains", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void anUnknownFlowIsNotFound() {
        assertThatThrownBy(() -> controller.cases("nope")).isInstanceOf(ResponseStatusException.class);
    }

    // ---------------------------------------------------------------- deleting

    @Test
    void deletingIsScopedToTheFlowInTheUrl() {
        when(dataset.get("c1")).thenReturn(Optional.of(aCase("c1", "f2", "contains")));

        assertThatThrownBy(() -> controller.delete("f1", "c1")).isInstanceOf(ResponseStatusException.class);
        verify(dataset, never()).delete(any());

        when(dataset.get("c2")).thenReturn(Optional.of(aCase("c2", "f1", "contains")));
        controller.delete("f1", "c2");
        verify(dataset).delete("c2");
    }

    // ---------------------------------------------------------------- results and running

    @Test
    void aResultOfAnotherFlowIsNotFoundThroughThisOne() {
        when(results.get("evr_1")).thenReturn(Optional.of(
                new FlowEvalResult("evr_1", "f2", 3, 1L, 2L, FlowEvalResult.DONE, List.of(), 0, 0)));

        assertThatThrownBy(() -> controller.result("f1", "evr_1")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void runningEvaluatesTheFlowAsSavedNow() {
        FlowEvalResult pending = new FlowEvalResult("evr_1", "f1", 3, 1L, null, FlowEvalResult.RUNNING, List.of(), 0, 2);
        when(evaluations.start(FLOW)).thenReturn(pending);

        assertThat(controller.run("f1")).isSameAs(pending);
    }
}
