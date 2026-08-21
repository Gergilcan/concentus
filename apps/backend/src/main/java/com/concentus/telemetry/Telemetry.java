package com.concentus.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * What this application says about itself, in OpenTelemetry's words.
 *
 * <p>The framework already instruments what a framework can see: an HTTP request, a scheduled
 * task, a WebSocket session. None of that answers the question anybody actually has here, which is
 * about a <em>run</em> — why it took eleven minutes, which block was slow, which tool call hung,
 * how many tokens the third worker spent, whether the knowledge base found anything. Those are this application's own units of work, and only
 * this application can name them.
 *
 * <p>So there is one place that knows how: six span names, one vocabulary of attributes, and a
 * handful of meters. Scattering {@code tracer.spanBuilder(...)} through the services would have
 * produced five spellings of "flow.id" inside a month, and a dashboard is only as good as the
 * agreement between the things it groups.
 *
 * <p><b>No prompts, no outputs, no arguments.</b> Attributes carry identifiers, counts and
 * outcomes — never the text that went to a model or came back from a tool. A trace ends up in
 * somebody else's system, usually with a longer retention than anything here and read by people
 * who were never given access to the flows themselves. What is useful for debugging and what is
 * safe to export are different sets, and where they differ the smaller one wins.
 *
 * <p>Exports nothing unless a collector is configured; see {@code management.otlp.*}. A desktop
 * install with no collector must not spend its evening retrying gRPC to localhost.
 */
@Component
public class Telemetry {

    private static final Logger log = LoggerFactory.getLogger(Telemetry.class);

    /** One run of a flow, from the trigger to its last block. */
    public static final String SPAN_RUN = "concentus.run";
    /** One block inside a run: an agent, a gate, a sub-flow, an API call. */
    public static final String SPAN_NODE = "concentus.node";
    /** One independent worker in a fan-out — its own process, its own context window. */
    public static final String SPAN_WORKER = "concentus.worker";
    /** One MCP tool call made by an agent. */
    public static final String SPAN_TOOL = "concentus.tool";
    /** One request to a model. */
    public static final String SPAN_MODEL = "concentus.model";
    /** One search of a knowledge base: both branches, the fusion, and any reranking. */
    public static final String SPAN_RETRIEVAL = "concentus.retrieval";

    // The attribute names, spelled once. A dashboard groups by these, and two spellings of the
    // same idea is a dashboard that quietly shows half the data.
    public static final String ATTR_FLOW_ID = "concentus.flow.id";
    public static final String ATTR_FLOW_NAME = "concentus.flow.name";
    public static final String ATTR_RUN_ID = "concentus.run.id";
    public static final String ATTR_NODE_ID = "concentus.node.id";
    public static final String ATTR_NODE_TYPE = "concentus.node.type";
    public static final String ATTR_AGENT = "concentus.agent.name";
    public static final String ATTR_MODEL = "concentus.model";
    public static final String ATTR_BACKEND = "concentus.backend";
    public static final String ATTR_STATUS = "concentus.status";
    public static final String ATTR_TOOL = "concentus.tool.name";
    public static final String ATTR_SERVER = "concentus.mcp.server";
    public static final String ATTR_ORGANIZATION = "concentus.organization.id";
    public static final String ATTR_TOKENS_IN = "concentus.tokens.input";
    public static final String ATTR_TOKENS_OUT = "concentus.tokens.output";
    public static final String ATTR_KNOWLEDGE_BASE = "concentus.knowledge.base";
    public static final String ATTR_RETRIEVAL_K = "concentus.retrieval.k";
    public static final String ATTR_RETRIEVAL_SEMANTIC = "concentus.retrieval.semantic.candidates";
    public static final String ATTR_RETRIEVAL_LEXICAL = "concentus.retrieval.lexical.candidates";
    public static final String ATTR_RETRIEVAL_RERANKED = "concentus.retrieval.reranked";

    private final Tracer tracer;
    private final MeterRegistry meters;

    public Telemetry(Tracer tracer, MeterRegistry meters) {
        this.tracer = tracer;
        this.meters = meters;
    }

    /**
     * A span that is open until it is closed, and closed whatever happens.
     *
     * <p>Returned rather than wrapped around a lambda because the work it measures is not a
     * function: a run starts on one thread, streams for minutes, and ends when a model stops
     * talking. What that needs is a handle, and what a handle needs is to be closeable exactly
     * once — hence try-with-resources at every call site.
     */
    public Scope start(String name) {
        Span span = tracer.nextSpan().name(name).start();
        return new Scope(span, tracer.withSpan(span));
    }

    /** Runs the callable inside a span, recording anything it throws. For work that is a function. */
    public <T> T inSpan(String name, Callable<T> work) throws Exception {
        try (Scope scope = start(name)) {
            try {
                return work.call();
            } catch (Exception e) {
                scope.failed(e);
                throw e;
            }
        }
    }

    /** A counter, by name and tags. Cheap enough to call on a hot path; registered once. */
    public void count(String name, String... tags) {
        meters.counter(name, tags).increment();
    }

    /** Adds to a counter — tokens, dollars, bytes. */
    public void add(String name, double amount, String... tags) {
        meters.counter(name, tags).increment(amount);
    }

    public Timer.Sample startTimer() {
        return Timer.start(meters);
    }

    public void stopTimer(Timer.Sample sample, String name, String... tags) {
        sample.stop(meters.timer(name, tags));
    }

    /**
     * An open span, and the thread-local scope that makes it the parent of whatever starts next.
     *
     * <p>Both are closed together, in that order, because leaking the scope is worse than leaking
     * the span: the span merely never ends, while an unclosed scope leaves this thread believing
     * it is inside a piece of work it has left — and the thread is pooled, so the next run picks up
     * the previous one's parent.
     */
    public static final class Scope implements AutoCloseable {

        private final Span span;
        private final Tracer.SpanInScope inScope;
        private boolean closed;

        private Scope(Span span, Tracer.SpanInScope inScope) {
            this.span = span;
            this.inScope = inScope;
        }

        /** Records an attribute. Null and blank values are dropped rather than exported as "null". */
        public Scope tag(String key, String value) {
            if (value != null && !value.isBlank()) span.tag(key, value);
            return this;
        }

        public Scope tag(String key, long value) {
            span.tag(key, String.valueOf(value));
            return this;
        }

        /** Marks the span as failed and records why. The message only — never a stack, never a body. */
        public Scope failed(Throwable error) {
            span.tag(ATTR_STATUS, "failed");
            span.error(error);
            return this;
        }

        /** The trace this work belongs to, for a log line or an error somebody has to correlate. */
        public String traceId() {
            return span.context().traceId();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                inScope.close();
            } finally {
                span.end();
            }
        }
    }

    /**
     * A Telemetry that records nothing, for constructing a service directly.
     *
     * <p>The alternative was threading a tracer into every test that builds a run, none of which
     * has anything to say about telemetry.
     */
    public static Telemetry none() {
        return new Telemetry(io.micrometer.tracing.otel.bridge.OtelTracer.NOOP,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** Whether anything is actually being exported, for the one log line that says so at startup. */
    public void announce(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            log.info("Telemetry is not being exported (no collector configured). "
                    + "Set one under Resources → Settings to send traces and metrics.");
        } else {
            log.info("Exporting traces and metrics to {}", endpoint);
        }
    }
}
