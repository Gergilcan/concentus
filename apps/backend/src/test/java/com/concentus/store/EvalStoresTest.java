package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.FlowEvalCase;
import com.concentus.model.FlowEvalCaseResult;
import com.concentus.model.FlowEvalResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round trips for {@link EvalDatasetStore} and {@link EvalResultStore} through the real resources
 * table: every component survives the JSON row (a record constructor that ate one would show
 * here), and a flow only ever sees its own cases and results.
 */
class EvalStoresTest {

    private EvalDatasetStore cases;
    private EvalResultStore results;

    @BeforeEach
    void setUp() {
        TestDatabase.reset(TestDatabase.jdbc());
        cases = new EvalDatasetStore(TestDatabase.jdbc(), new ObjectMapper(), new OrgContext("default"));
        cases.init();
        results = new EvalResultStore(TestDatabase.jdbc(), new ObjectMapper(), new OrgContext("default"));
        results.init();
    }

    @Test
    void aCaseComesBackWholeAndOnlyToItsFlow() {
        FlowEvalCase saved = cases.save(new FlowEvalCase(null, "f1", "Totals", "sum the invoices",
                "120 EUR", "contains", 10L));
        cases.save(new FlowEvalCase(null, "f2", "Other flow", "in", "out", "exact", 20L));

        assertThat(saved.id()).startsWith("evc_");
        List<FlowEvalCase> mine = cases.listForFlow("f1");
        assertThat(mine).hasSize(1);
        FlowEvalCase back = mine.get(0);
        assertThat(back.flowId()).isEqualTo("f1");
        assertThat(back.name()).isEqualTo("Totals");
        assertThat(back.input()).isEqualTo("sum the invoices");
        assertThat(back.expected()).isEqualTo("120 EUR");
        assertThat(back.judge()).isEqualTo("contains");
        assertThat(back.createdAt()).isEqualTo(10L);
    }

    @Test
    void casesListInTheOrderTheyWereWrittenWhateverTheirNames() {
        cases.save(new FlowEvalCase(null, "f1", "Zebra first", "in", "out", "contains", 1L));
        cases.save(new FlowEvalCase(null, "f1", "Apple second", "in", "out", "contains", 2L));

        assertThat(cases.listForFlow("f1")).extracting(FlowEvalCase::name)
                .containsExactly("Zebra first", "Apple second");
    }

    @Test
    void savingWithAnIdReplacesTheCaseInPlace() {
        FlowEvalCase first = cases.save(new FlowEvalCase(null, "f1", "Draft", "in", "out", "contains", 1L));

        cases.save(new FlowEvalCase(first.id(), "f1", "Final", "in", "out", "regex", 1L));

        assertThat(cases.listForFlow("f1")).hasSize(1);
        assertThat(cases.get(first.id()).orElseThrow().name()).isEqualTo("Final");
        assertThat(cases.get(first.id()).orElseThrow().judge()).isEqualTo("regex");
    }

    @Test
    void aResultComesBackWithItsCasesAndNewestFirst() {
        FlowEvalCaseResult judged = new FlowEvalCaseResult("c1", "Totals", "run_a", true,
                "Found \"120 EUR\" in the output.", "The total is 120 EUR.");
        FlowEvalResult older = results.save(new FlowEvalResult(null, "f1", 7, 100L, 150L,
                FlowEvalResult.DONE, List.of(judged), 1, 1));
        FlowEvalResult newer = results.save(new FlowEvalResult(null, "f1", 8, 200L, null,
                FlowEvalResult.RUNNING, List.of(), 0, 1));
        results.save(new FlowEvalResult(null, "f2", 1, 300L, 301L, FlowEvalResult.DONE, List.of(), 0, 0));

        List<FlowEvalResult> mine = results.listForFlow("f1");

        assertThat(mine).extracting(FlowEvalResult::id).containsExactly(newer.id(), older.id());
        FlowEvalResult back = mine.get(1);
        assertThat(back.flowVersion()).isEqualTo(7);
        assertThat(back.finishedAt()).isEqualTo(150L);
        assertThat(back.passed()).isEqualTo(1);
        assertThat(back.cases()).containsExactly(judged);
        // Still running: no finish time, and the JSON must say so rather than invent one.
        assertThat(mine.get(0).finishedAt()).isNull();
        assertThat(mine.get(0).status()).isEqualTo(FlowEvalResult.RUNNING);
    }

    @Test
    void aResultIsRewrittenUnderItsIdAsItsCasesFinish() {
        FlowEvalResult pending = results.save(new FlowEvalResult(null, "f1", 7, 100L, null,
                FlowEvalResult.RUNNING, List.of(), 0, 2));

        results.save(new FlowEvalResult(pending.id(), "f1", 7, 100L, 160L, FlowEvalResult.DONE,
                List.of(new FlowEvalCaseResult("c1", "A", "run_a", false, "no", null),
                        new FlowEvalCaseResult("c2", "B", "run_b", true, "yes", "ok")), 1, 2));

        assertThat(results.listForFlow("f1")).hasSize(1);
        FlowEvalResult done = results.get(pending.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(FlowEvalResult.DONE);
        assertThat(done.cases()).hasSize(2);
        assertThat(done.cases().get(0).output()).isNull();
    }
}
