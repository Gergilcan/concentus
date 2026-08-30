package com.concentus.web;

import com.concentus.audit.AuditKinds;
import com.concentus.audit.AuditService;
import com.concentus.auth.OrgContext;
import com.concentus.mail.MailTriggerService;
import com.concentus.model.FlowGraph;
import com.concentus.service.RunService;
import com.concentus.service.ScheduleService;
import com.concentus.store.FlowMemoryStore;
import com.concentus.store.FlowStore;
import com.concentus.store.FlowVersionStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FlowController}'s version-history endpoints: who a saved revision is
 * credited to, that restoring appends rather than rewrites, and that previewing a revision
 * persists nothing. Hand-wired mocks — no Spring context, no database.
 */
class FlowControllerTest {

    private final FlowStore store = mock(FlowStore.class);
    private final RunService runService = mock(RunService.class);
    private final ScheduleService scheduler = mock(ScheduleService.class);
    private final MailTriggerService mailTriggers = mock(MailTriggerService.class);
    private final FlowVersionStore versions = mock(FlowVersionStore.class);
    private final FlowMemoryStore memory = mock(FlowMemoryStore.class);

    private static FlowGraph flow(String id, String name) {
        return new FlowGraph(id, name, List.of(), List.of(), null, List.of(), null, null);
    }

    private final com.concentus.service.GoldenStatusService goldenStatus =
            mock(com.concentus.service.GoldenStatusService.class);
    private final AuditService audit = mock(AuditService.class);

    private FlowController controller() {
        return new FlowController(store, runService, scheduler, mailTriggers, versions, memory,
                new OrgContext("default"),
                mock(com.concentus.service.FlowGenerator.class), goldenStatus,
                mock(com.concentus.service.FlowDoctor.class),
                mock(com.concentus.service.FolderWatchService.class), audit);
    }

    // A wrong name on a revision is worse than no name: it is the field people read to work out
    // what changed and who to ask.
    @Test
    void savingLeavesTheRevisionUnsignedWhenNobodyIsOnTheRequest() {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller().save(flow("f1", "My Flow"));

        verify(versions).snapshot(any(), eq(null));
    }

    @Test
    void savingLeavesTheRevisionUnsignedWhenAuthIsOnButNobodyIsOnTheRequest() {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // No principal on this thread: rather than crediting the revision to the default account,
        // it is stored unsigned and the history shows "—".
        controller().save(flow("f1", "My Flow"));

        verify(versions).snapshot(any(), eq((String) null));
    }

    @Test
    void restoringSavesTheOldRevisionAndAppendsItToTheHistoryAsANewOne() {
        when(versions.get("f1", 1)).thenReturn(Optional.of(flow("f1", "Original")));
        when(store.get("f1")).thenReturn(Optional.of(flow("f1", "Current")));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlowGraph restored = controller().restore("f1", 1);

        assertThat(restored.name()).isEqualTo("Original");
        // Appending the restore as its own revision is what keeps history additive: the version
        // that was current when Restore was pressed stays in the list, recoverable.
        verify(versions).snapshot(any(), eq(null));
        verify(scheduler).reschedule();
    }

    @Test
    void previewingARevisionReturnsItWithoutSavingOrSnapshottingAnything() {
        when(versions.get("f1", 2)).thenReturn(Optional.of(flow("f1", "Two versions ago")));
        when(store.get("f1")).thenReturn(Optional.of(flow("f1", "Current")));

        FlowGraph preview = controller().version("f1", 2);

        assertThat(preview.name()).isEqualTo("Two versions ago");
        assertThat(preview.id()).isEqualTo("f1");
        verify(store, never()).save(any());
        verify(versions, never()).snapshot(any(), any());
    }

    @Test
    void previewingAVersionThatIsNotThereIs404RatherThanAnEmptyCanvas() {
        when(versions.get("f1", 9)).thenReturn(Optional.empty());
        when(store.get("f1")).thenReturn(Optional.of(flow("f1", "Current")));

        assertThatThrownBy(() -> controller().version("f1", 9))
                .hasMessageContaining("No such version");
    }

    // ---------------------------------------------------------------- automatic golden checks

    @Test
    void savingAFlowThatOptedInReplaysItsGoldenReference() {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(store.get("f1")).thenReturn(Optional.of(flow("f1", "Before")));
        com.concentus.service.AgentRun golden =
                new com.concentus.service.AgentRun("run_g", "f1", "Flow");
        when(goldenStatus.autoCheckAfterSave(any(), any())).thenReturn(Optional.of(golden));

        controller().save(flow("f1", "After"));

        verify(runService).startGoldenCheck(any(), eq("run_g"));
    }

    @Test
    void aGoldenCheckThatCannotStartDoesNotFailTheSave() {
        // The edit is what the user asked for; the check is the favour. A favour that eats the
        // request — no CLI, budget reached — would lose their work.
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(store.get("f1")).thenReturn(Optional.of(flow("f1", "Before")));
        com.concentus.service.AgentRun golden =
                new com.concentus.service.AgentRun("run_g", "f1", "Flow");
        when(goldenStatus.autoCheckAfterSave(any(), any())).thenReturn(Optional.of(golden));
        when(runService.startGoldenCheck(any(), eq("run_g")))
                .thenThrow(new IllegalStateException("Monthly budget reached."));

        FlowGraph saved = controller().save(flow("f1", "After"));

        assertThat(saved.name()).isEqualTo("After");
    }

    @Test
    void savingAFlowThatDidNotOptInStartsNothing() {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(goldenStatus.autoCheckAfterSave(any(), any())).thenReturn(Optional.empty());

        controller().save(flow("f1", "After"));

        verify(runService, never()).startGoldenCheck(any(), any());
    }

    // ---------------------------------------------------------------- audit trail

    private static FlowGraph published(String id, boolean published, String token) {
        return new FlowGraph(id, "Endpoint flow",
                List.of(new com.concentus.model.FlowNode("in", "input", null,
                        java.util.Map.of("mode", "manual", "published", published, "publishToken", token))),
                List.of(), null, List.of(), null, null);
    }

    @Test
    void aFirstSaveIsRecordedAsCreatedAndALaterOneAsSavedWithItsVersion() {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versions.currentVersion("f1")).thenReturn(4);

        controller().save(flow(null, "Brand new"));
        verify(audit).record(eq(AuditKinds.FLOW_CREATED), eq("flow"), org.mockito.ArgumentMatchers.isNull(),
                eq("Brand new"), any());

        when(store.get("f1")).thenReturn(Optional.of(flow("f1", "Before")));
        controller().save(flow("f1", "After"));
        verify(audit).record(eq(AuditKinds.FLOW_SAVED), eq("flow"), eq("f1"), eq("After"),
                eq(java.util.Map.of("version", 4)));
    }

    // Publishing is a toggle on the input node saved with the rest of the graph; the only place
    // that can tell "published" from "renamed" is the save, with before and after side by side.
    @Test
    void publishingUnpublishingAndRegeneratingTheTokenAreTheirOwnRows() {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(store.get("f1")).thenReturn(Optional.of(published("f1", false, "")));
        controller().save(published("f1", true, "tok-1"));
        verify(audit).record(eq(AuditKinds.FLOW_PUBLISHED), eq("flow"), eq("f1"), any(), any());

        when(store.get("f1")).thenReturn(Optional.of(published("f1", true, "tok-1")));
        controller().save(published("f1", true, "tok-2"));
        verify(audit).record(eq(AuditKinds.FLOW_TOKEN_REGENERATED), eq("flow"), eq("f1"), any(), any());

        when(store.get("f1")).thenReturn(Optional.of(published("f1", true, "tok-2")));
        controller().save(published("f1", false, "tok-2"));
        verify(audit).record(eq(AuditKinds.FLOW_UNPUBLISHED), eq("flow"), eq("f1"), any(), any());

        // An ordinary save of a published flow — same token, same toggle — adds no publish row.
        when(store.get("f1")).thenReturn(Optional.of(published("f1", true, "tok-2")));
        controller().save(published("f1", true, "tok-2"));
        verify(audit, org.mockito.Mockito.times(1)).record(eq(AuditKinds.FLOW_PUBLISHED), any(), any(), any(), any());
        verify(audit, org.mockito.Mockito.times(1)).record(eq(AuditKinds.FLOW_TOKEN_REGENERATED), any(), any(), any(), any());
        // The token itself never reaches the trail.
        verify(audit, never()).record(any(), any(), any(), eq("tok-1"), any());
        verify(audit, never()).record(any(), any(), any(), eq("tok-2"), any());
    }

    @Test
    void deletingRecordsTheNameTheFlowHadBeforeItWasGone() {
        when(store.get("f1")).thenReturn(Optional.of(flow("f1", "Doomed")));

        controller().delete("f1");

        verify(audit).record(eq(AuditKinds.FLOW_DELETED), eq("flow"), eq("f1"), eq("Doomed"), any());
    }
}
