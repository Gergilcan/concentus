package com.concentus.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How much a flow's agents may do without asking.
 *
 * <p>The validation is the point. This value becomes a {@code --permission-mode} argument to the
 * claude CLI, and the failure everyone should care about is a typo that <em>widens</em> what a flow
 * may do — so anything unrecognised falls back to the deployment default rather than being passed
 * through or guessed at.
 */
class PermissionModeTest {

    private static FlowGraph flowWith(FlowNode... nodes) {
        return new FlowGraph("f1", "Flow", List.of(nodes), List.of(),
                null, List.of(), null, null);
    }

    private static TriggerSpec specWith(String permissionMode) {
        return TriggerSpec.from(flowWith(new FlowNode("in-1", "input", null,
                Map.of("mode", "manual", "permissionMode", permissionMode))));
    }

    @Test
    void everyDocumentedModeIsAccepted() {
        for (String mode : TriggerSpec.PERMISSION_MODES) {
            assertThat(specWith(mode).permissionMode()).isEqualTo(mode);
        }
    }

    @Test
    void anUnrecognisedModeFallsBackToTheDeploymentDefault() {
        // Not passed through: the CLI would reject it at launch, and a run that fails to start is
        // a better outcome than one that starts with permissions nobody chose.
        assertThat(specWith("bypass").permissionMode()).isEmpty();
        assertThat(specWith("BYPASSPERMISSIONS").permissionMode()).isEmpty();
        assertThat(specWith("--dangerously-skip-permissions").permissionMode()).isEmpty();
    }

    @Test
    void aFlowThatNamesNoneDefersToTheDeployment() {
        // Every flow saved before this existed, which must keep behaving exactly as it did.
        assertThat(specWith("").permissionMode()).isEmpty();
        assertThat(TriggerSpec.from(flowWith(new FlowNode("in-1", "input", null,
                Map.of("mode", "manual")))).permissionMode()).isEmpty();
    }

    @Test
    void surroundingSpaceDoesNotDefeatTheMatch() {
        assertThat(specWith("  plan  ").permissionMode()).isEqualTo("plan");
    }

    @Test
    void aFlowWithNoInputNodeStillHasASpec() {
        assertThat(TriggerSpec.from(flowWith()).permissionMode()).isEmpty();
    }
}
