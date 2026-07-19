package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.SqlSourceSpec;
import com.concentus.model.NodeExec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RagContextInjector}: runs each SQL source through a mocked
 * {@link SqlRagRetriever} (no real JDBC connection) and checks the agent's system prompt is
 * extended, the caller is notified via the emit callback, and (when a run is supplied) the
 * per-node execution record is populated for the UI's Input/Output tabs.
 */
class RagContextInjectorTest {

    private final SqlRagRetriever retriever = mock(SqlRagRetriever.class);
    private final RagContextInjector injector = new RagContextInjector(retriever);

    private static SqlSourceSpec sqlSource(String nodeId, String label, String query) {
        SqlSourceSpec s = new SqlSourceSpec();
        s.nodeId = nodeId;
        s.label = label;
        s.jdbcUrl = "jdbc:postgresql://db/app";
        s.query = query;
        return s;
    }

    private static AgentSpec specWithSources(SqlSourceSpec... sources) {
        AgentSpec s = new AgentSpec();
        s.name = "Agent";
        s.systemPrompt = "Base prompt.";
        s.ragSources = new ArrayList<>(List.of(sources));
        return s;
    }

    @Test
    void noSourcesMeansNoRetrieverCallsAndPromptUnchanged() {
        AgentSpec spec = specWithSources();
        List<String> emitted = new ArrayList<>();

        injector.inject(spec, emitted::add);

        assertThat(spec.systemPrompt).isEqualTo("Base prompt.");
        assertThat(emitted).isEmpty();
        verifyNoInteractions(retriever);
    }

    @Test
    void successfulQueryAppendsContextAndEmitsRowCount() throws Exception {
        SqlSourceSpec source = sqlSource("sql1", "orders", "select 1");
        AgentSpec spec = specWithSources(source);
        SqlRagRetriever.TableResult result = new SqlRagRetriever.TableResult(
                List.of("id"), List.of(List.of("1"), List.of("2")), false);
        when(retriever.query(source)).thenReturn(result);
        when(retriever.asContextText(source, result)).thenReturn("id\n---\n1\n2\n");
        List<String> emitted = new ArrayList<>();

        injector.inject(spec, emitted::add);

        assertThat(spec.systemPrompt)
                .startsWith("Base prompt.")
                .contains("Retrieved context — orders")
                .contains("id\n---\n1\n2\n");
        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0)).contains("orders").contains("2 row(s)");
    }

    @Test
    void failedQueryAppendsUnavailableNoteAndEmitsFailureInsteadOfThrowing() throws Exception {
        SqlSourceSpec source = sqlSource("sql1", "orders", "select 1");
        AgentSpec spec = specWithSources(source);
        when(retriever.query(source)).thenThrow(new RuntimeException("connection refused"));
        List<String> emitted = new ArrayList<>();

        injector.inject(spec, emitted::add);

        assertThat(spec.systemPrompt).contains("orders").contains("unavailable: connection refused");
        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0)).contains("query failed").contains("connection refused");
    }

    @Test
    void multipleSourcesAreEachAppendedInOrder() throws Exception {
        SqlSourceSpec first = sqlSource("sql1", "first", "select 1");
        SqlSourceSpec second = sqlSource("sql2", "second", "select 2");
        AgentSpec spec = specWithSources(first, second);
        SqlRagRetriever.TableResult r1 = new SqlRagRetriever.TableResult(List.of("a"), List.of(), false);
        SqlRagRetriever.TableResult r2 = new SqlRagRetriever.TableResult(List.of("b"), List.of(), false);
        when(retriever.query(first)).thenReturn(r1);
        when(retriever.query(second)).thenReturn(r2);
        when(retriever.asContextText(first, r1)).thenReturn("text-a");
        when(retriever.asContextText(second, r2)).thenReturn("text-b");

        injector.inject(spec, s -> { });

        assertThat(spec.systemPrompt.indexOf("first")).isLessThan(spec.systemPrompt.indexOf("second"));
        assertThat(spec.systemPrompt).contains("text-a").contains("text-b");
    }

    @Test
    void whenARunIsSuppliedTheNodeExecIsPopulatedOnSuccess() throws Exception {
        SqlSourceSpec source = sqlSource("sql1", "orders", "select 1");
        AgentSpec spec = specWithSources(source);
        SqlRagRetriever.TableResult result = new SqlRagRetriever.TableResult(
                List.of("id"), List.of(List.of("1")), true);
        when(retriever.query(source)).thenReturn(result);
        when(retriever.asContextText(source, result)).thenReturn("id\n---\n1\n");
        AgentRun run = new AgentRun("run1", "flow1", "Flow", "local");

        injector.inject(spec, run, s -> { });

        NodeExec exec = run.nodeExec("sql1", "sql", "orders");
        assertThat(exec.status).isEqualTo("passed");
        assertThat(exec.format).isEqualTo("table");
        assertThat(exec.columns).containsExactly("id");
        assertThat(exec.output).contains("1 row(s)").contains("truncated");
        assertThat(exec.endedAt).isGreaterThan(0);
    }

    @Test
    void whenARunIsSuppliedTheNodeExecIsPopulatedOnFailure() throws Exception {
        SqlSourceSpec source = sqlSource("sql1", "orders", "select 1");
        AgentSpec spec = specWithSources(source);
        when(retriever.query(any())).thenThrow(new RuntimeException("boom"));
        AgentRun run = new AgentRun("run1", "flow1", "Flow", "local");

        injector.inject(spec, run, s -> { });

        NodeExec exec = run.nodeExec("sql1", "sql", "orders");
        assertThat(exec.status).isEqualTo("failed");
        assertThat(exec.error).isEqualTo("boom");
        assertThat(exec.endedAt).isGreaterThan(0);
    }
}
