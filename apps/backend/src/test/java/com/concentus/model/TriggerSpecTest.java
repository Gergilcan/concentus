package com.concentus.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TriggerSpec#from(FlowGraph)}: extracting the trigger config from a flow's
 * {@code input} node, and the derived {@code autoStart()}/{@code scheduled()}/{@code webhook()}
 * accessors for each trigger mode.
 */
class TriggerSpecTest {

    private static FlowNode inputNode(Map<String, Object> data) {
        return new FlowNode("in1", "input", null, data);
    }

    private static FlowGraph flowWith(FlowNode... nodes) {
        return new FlowGraph("f1", "Flow", "managed", List.of(nodes), List.of(),
                null, List.of(), null, null);
    }

    // ------------------------------------------------------------- no input node

    @Test
    void noInputNodeDefaultsToManualWithBlankFields() {
        FlowGraph flow = flowWith();

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.mode()).isEqualTo("manual");
        assertThat(spec.prompt()).isEmpty();
        assertThat(spec.cron()).isEmpty();
        assertThat(spec.secret()).isEmpty();
        assertThat(spec.authParam()).isEqualTo(TriggerSpec.DEFAULT_AUTH_PARAM);
        assertThat(spec.autoStart()).isFalse();
        assertThat(spec.scheduled()).isFalse();
        assertThat(spec.webhook()).isFalse();
    }

    // ------------------------------------------------------------- manual mode

    @Test
    void manualModeNeverAutoStarts() {
        FlowGraph flow = flowWith(inputNode(Map.of("mode", "manual", "prompt", "hello")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.mode()).isEqualTo("manual");
        assertThat(spec.autoStart()).isFalse();
        assertThat(spec.scheduled()).isFalse();
        assertThat(spec.webhook()).isFalse();
    }

    // ------------------------------------------------------------- prompt mode

    @Test
    void promptModeAutoStartsWhenPromptIsPresent() {
        FlowGraph flow = flowWith(inputNode(Map.of("mode", "prompt", "prompt", "go")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.autoStart()).isTrue();
        assertThat(spec.scheduled()).isFalse();
        assertThat(spec.webhook()).isFalse();
    }

    @Test
    void promptModeDoesNotAutoStartWhenPromptIsMissing() {
        FlowGraph flow = flowWith(inputNode(Map.of("mode", "prompt")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.prompt()).isEmpty();
        assertThat(spec.autoStart()).isFalse();
    }

    @Test
    void promptModeDoesNotAutoStartWhenPromptIsBlank() {
        FlowGraph flow = flowWith(inputNode(Map.of("mode", "prompt", "prompt", "   ")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.autoStart()).isFalse();
    }

    // ------------------------------------------------------------- cron mode

    @Test
    void cronModeAutoStartsAndIsScheduledWhenBothPromptAndCronArePresent() {
        FlowGraph flow = flowWith(inputNode(Map.of("mode", "cron", "prompt", "go", "cron", "0 0 * * *")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.autoStart()).isTrue();
        assertThat(spec.scheduled()).isTrue();
        assertThat(spec.webhook()).isFalse();
    }

    @Test
    void cronModeIsNotScheduledWhenCronIsMissing() {
        FlowGraph flow = flowWith(inputNode(Map.of("mode", "cron", "prompt", "go")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.autoStart()).isTrue();
        assertThat(spec.scheduled()).isFalse();
    }

    @Test
    void cronModeDoesNotAutoStartWhenPromptIsMissing() {
        FlowGraph flow = flowWith(inputNode(Map.of("mode", "cron", "cron", "0 0 * * *")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.autoStart()).isFalse();
        assertThat(spec.scheduled()).isTrue();
    }

    // ------------------------------------------------------------- webhook mode

    @Test
    void webhookModeIsWebhookRegardlessOfPrompt() {
        FlowGraph flow = flowWith(inputNode(Map.of("mode", "webhook")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.webhook()).isTrue();
        assertThat(spec.autoStart()).isFalse();
        assertThat(spec.scheduled()).isFalse();
    }

    @Test
    void webhookModeUsesCustomAuthParamAndSecretWhenProvided() {
        FlowGraph flow = flowWith(inputNode(
                Map.of("mode", "webhook", "secret", "shh", "authParam", "X-My-Signature")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.secret()).isEqualTo("shh");
        assertThat(spec.authParam()).isEqualTo("X-My-Signature");
    }

    @Test
    void webhookModeFallsBackToDefaultAuthParamWhenBlank() {
        FlowGraph flow = flowWith(inputNode(Map.of("mode", "webhook", "authParam", "   ")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.authParam()).isEqualTo(TriggerSpec.DEFAULT_AUTH_PARAM);
    }

    // ------------------------------------------------------------- mode is case-insensitive

    @Test
    void modeMatchingIsCaseInsensitive() {
        FlowGraph flow = flowWith(inputNode(Map.of("mode", "PROMPT", "prompt", "go")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.autoStart()).isTrue();
    }

    // ------------------------------------------------------------- missing mode field

    @Test
    void missingModeFieldDefaultsToManual() {
        FlowGraph flow = flowWith(inputNode(Map.of("prompt", "go")));

        TriggerSpec spec = TriggerSpec.from(flow);

        assertThat(spec.mode()).isEqualTo("manual");
        assertThat(spec.autoStart()).isFalse();
    }
}
