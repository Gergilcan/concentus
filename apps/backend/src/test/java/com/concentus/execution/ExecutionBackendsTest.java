package com.concentus.execution;

import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolving a model to the backend that will run it.
 *
 * <p>This is what lets a deployment run only the pieces it needs: a backend that isn't configured
 * reports itself unavailable and must then be invisible both to routing and to the designer.
 */
class ExecutionBackendsTest {

    /** A backend that claims models by prefix and can be toggled available. */
    private static final class StubBackend implements ExecutionBackend {
        private final String id;
        private final String prefix;
        private final boolean available;

        StubBackend(String id, String prefix, boolean available) {
            this.id = id;
            this.prefix = prefix;
            this.available = available;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public boolean supportsModel(String model) {
            return model != null && model.startsWith(prefix);
        }

        @Override
        public void runTurn(AgentRun run, CompiledFlow flow, String userText) {
            // routing is what's under test
        }
    }

    @Test
    void resolvesAModelToTheBackendThatClaimsIt() {
        ExecutionBackends backends = new ExecutionBackends(List.of(
                new StubBackend("local", "claude-", true),
                new StubBackend("api", "gpt-", true)));

        assertThat(backends.forModel("claude-opus-4-8").map(ExecutionBackend::id)).contains("local");
        assertThat(backends.forModel("gpt-5.6-sol").map(ExecutionBackend::id)).contains("api");
    }

    @Test
    void anUnavailableBackendNeverWinsAModelItClaims() {
        // Routing to a backend that can't execute would send the run somewhere it dies; better to
        // resolve nothing and fail at launch with a clear message.
        ExecutionBackends backends = new ExecutionBackends(List.of(
                new StubBackend("local", "claude-", false)));

        assertThat(backends.forModel("claude-opus-4-8")).isEmpty();
        assertThat(backends.available()).isEmpty();
        // It stays listed, so the designer can say "installed but not available" rather than
        // pretending the backend doesn't exist.
        assertThat(backends.all()).hasSize(1);
    }

    @Test
    void anUnknownModelResolvesToNothing() {
        ExecutionBackends backends = new ExecutionBackends(List.of(
                new StubBackend("local", "claude-", true)));

        assertThat(backends.forModel("llama-3")).isEmpty();
        assertThat(backends.forModel(null)).isEmpty();
    }

    @Test
    void anAvailableBackendWinsOverAnUnavailableOneClaimingTheSameModel() {
        // The realistic case for this: the Claude CLI isn't installed here, but a provider key is,
        // so a claude- model should still find a home rather than resolving to the dead backend.
        ExecutionBackends backends = new ExecutionBackends(List.of(
                new StubBackend("local", "claude-", false),
                new StubBackend("api", "claude-", true)));

        assertThat(backends.forModel("claude-opus-4-8").map(ExecutionBackend::id)).contains("api");
    }

    @Test
    void backendsReportThemselvesForTheDesigner() {
        ExecutionBackends backends = new ExecutionBackends(List.of(
                new StubBackend("local", "claude-", true),
                new StubBackend("copilot", "copilot-", false)));

        assertThat(backends.available()).extracting(ExecutionBackend::id).containsExactly("local");
        assertThat(backends.byId("copilot")).isPresent();
        assertThat(backends.byId("nope")).isEmpty();
    }
}
