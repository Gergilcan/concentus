package com.concentus.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What this application says about itself.
 *
 * <p>Two things are worth holding still. A span has to close on every path, because the failure
 * mode of one that does not is not a missing trace — it is a pooled thread that believes it is
 * still inside work it has left, so the next run is filed underneath the previous one. And the
 * attributes are a vocabulary: a dashboard is only as good as the agreement between the things it
 * groups, and two spellings of the same idea is a dashboard quietly showing half the data.
 */
class TelemetryTest {

    private final SimpleTracer tracer = new SimpleTracer();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Telemetry telemetry = new Telemetry(tracer, meters);

    @Test
    void a_span_carries_the_name_and_the_attributes_it_was_given() {
        try (Telemetry.Scope scope = telemetry.start(Telemetry.SPAN_RUN)) {
            scope.tag(Telemetry.ATTR_RUN_ID, "run_1").tag(Telemetry.ATTR_TOKENS_IN, 1200);
        }

        var span = tracer.onlySpan();
        assertThat(span.getName()).isEqualTo("concentus.run");
        assertThat(span.getTags()).containsEntry(Telemetry.ATTR_RUN_ID, "run_1")
                .containsEntry(Telemetry.ATTR_TOKENS_IN, "1200");
        assertThat(span.getEndTimestamp()).isNotNull();
    }

    // Exported as "null" it would be a value somebody filters on and finds nothing under.
    @Test
    void an_absent_attribute_is_left_out_rather_than_exported_as_nothing() {
        try (Telemetry.Scope scope = telemetry.start(Telemetry.SPAN_NODE)) {
            scope.tag(Telemetry.ATTR_MODEL, null).tag(Telemetry.ATTR_AGENT, "   ");
        }

        assertThat(tracer.onlySpan().getTags())
                .doesNotContainKey(Telemetry.ATTR_MODEL)
                .doesNotContainKey(Telemetry.ATTR_AGENT);
    }

    @Test
    void work_that_throws_ends_its_span_and_says_it_failed() {
        assertThatThrownBy(() -> telemetry.inSpan(Telemetry.SPAN_TOOL, () -> {
            throw new IllegalStateException("the server refused");
        })).isInstanceOf(IllegalStateException.class);

        var span = tracer.onlySpan();
        assertThat(span.getEndTimestamp()).isNotNull();
        assertThat(span.getTags()).containsEntry(Telemetry.ATTR_STATUS, "failed");
    }

    // Closing twice happens: a try-with-resources inside a method that also closes on a failure
    // path. Ending a span twice is an exporter's problem, so it is this class's problem first.
    @Test
    void closing_a_scope_twice_ends_the_span_once() {
        Telemetry.Scope scope = telemetry.start(Telemetry.SPAN_MODEL);
        scope.close();
        scope.close();

        assertThat(tracer.getSpans()).hasSize(1);
    }

    @Test
    void counters_carry_their_tags() {
        telemetry.count("concentus.runs.finished", "status", "COMPLETED");
        telemetry.add("concentus.tokens", 900, "direction", "output");

        assertThat(meters.get("concentus.runs.finished").tag("status", "COMPLETED").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("concentus.tokens").tag("direction", "output").counter().count())
                .isEqualTo(900);
    }

    // Every service can be built without a tracer, because most tests have nothing to say about
    // telemetry and should not have to mention it.
    @Test
    void a_telemetry_that_records_nothing_still_works() {
        Telemetry none = Telemetry.none();

        try (Telemetry.Scope scope = none.start(Telemetry.SPAN_RUN)) {
            scope.tag(Telemetry.ATTR_RUN_ID, "run_1");
        }
        none.count("concentus.runs.finished", "status", "COMPLETED");
    }

    /**
     * The names are the contract with whatever collects them. Renaming one is not a refactor: it
     * is a dashboard that stops showing anything, weeks later, for reasons nobody connects to a
     * commit here.
     */
    @Test
    void the_span_names_are_the_ones_dashboards_are_built_on() {
        assertThat(Telemetry.SPAN_RUN).isEqualTo("concentus.run");
        assertThat(Telemetry.SPAN_NODE).isEqualTo("concentus.node");
        assertThat(Telemetry.SPAN_WORKER).isEqualTo("concentus.worker");
        assertThat(Telemetry.SPAN_TOOL).isEqualTo("concentus.tool");
        assertThat(Telemetry.SPAN_MODEL).isEqualTo("concentus.model");
    }
}
