package com.concentus.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The order a ceiling is measured against, and the two edges that matter: blank and unknown. */
class PermissionCeilingTest {

    @Test
    void theOrderRunsFromPlanToBypass() {
        assertThat(PermissionCeiling.above("bypassPermissions", "acceptEdits")).isTrue();
        assertThat(PermissionCeiling.above("acceptEdits", "acceptEdits")).isFalse();
        assertThat(PermissionCeiling.above("default", "acceptEdits")).isFalse();
        assertThat(PermissionCeiling.above("plan", "default")).isFalse();
        assertThat(PermissionCeiling.clamp("bypassPermissions", "plan")).isEqualTo("plan");
        assertThat(PermissionCeiling.clamp("plan", "bypassPermissions")).isEqualTo("plan");
    }

    @Test
    void aBlankCeilingClampsNothing() {
        assertThat(PermissionCeiling.above("bypassPermissions", "")).isFalse();
        assertThat(PermissionCeiling.above("bypassPermissions", null)).isFalse();
        assertThat(PermissionCeiling.clamp("bypassPermissions", null)).isEqualTo("bypassPermissions");
    }

    @Test
    void anUnknownModeIsNeverAboveAnything() {
        // The safe direction: a typo must not read as more permissive than a real mode.
        assertThat(PermissionCeiling.above("bypass", "plan")).isFalse();
        assertThat(PermissionCeiling.known("bypass")).isFalse();
        assertThat(PermissionCeiling.known(" acceptEdits ")).isTrue();
    }
}
