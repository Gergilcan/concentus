package com.concentus.service;

import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.llm.ChatTypes;
import com.concentus.model.FacadeProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that decide whether a worker's tool call executes. The one place in the feature
 * where a wrong answer is an unauthorized write, so the matrix is spelled out case by case.
 */
class FacadeToolGateTest {

    private static McpServerSpec server(String... nodeFilter) {
        McpServerSpec s = new McpServerSpec();
        s.name = "holded";
        s.url = "https://mcp.example.com";
        s.tools = List.of(nodeFilter);
        return s;
    }

    private static FacadeProfile profile(List<String> tools, boolean readOnly, Boolean dryRun) {
        return new FacadeProfile("p1", "test", "", tools, readOnly, dryRun);
    }

    private static List<ChatTypes.ToolSpec> tools(String... names) {
        return java.util.Arrays.stream(names)
                .map(n -> new ChatTypes.ToolSpec(n, "", null)).toList();
    }

    private static List<String> names(List<ChatTypes.ToolSpec> specs) {
        return specs.stream().map(ChatTypes.ToolSpec::name).toList();
    }

    // ------------------------------------------------------------ classification

    @Test
    void readVerbsAreReads() {
        for (String name : List.of("get_contact", "list_invoices", "search_contacts",
                "find_thing", "read_file", "fetch_page", "query_db", "preview_inbox_file",
                "download_inbox_file", "describe_table", "show_x", "count_rows",
                "check_status", "status", "history_of", "ping")) {
            assertThat(FacadeToolGate.isReadTool(name)).as(name).isTrue();
        }
    }

    @Test
    void everythingElseIsAWriteIncludingTheUnrecognized() {
        // Fail closed: misreading a write as a read executes it, which is the direction this
        // must never fail in. The odd ones are the point — none carries a write verb.
        for (String name : List.of("create_invoice", "update_contact", "delete_contact",
                "send_invoice", "approve_invoice", "clock_in", "convert_document",
                "reconcile_transaction_with_document", "pause", "frobnicate", "")) {
            assertThat(FacadeToolGate.isReadTool(name)).as(name).isFalse();
        }
    }

    // ------------------------------------------------------------ visibility

    @Test
    void nodeFilterAndProfileAllowlistBothApply() {
        List<ChatTypes.ToolSpec> offered =
                tools("get_contact", "list_contacts", "list_invoices", "create_contact");

        // Node says "contact", profile says "list": only the intersection survives.
        List<ChatTypes.ToolSpec> visible = FacadeToolGate.visible(
                offered, server("contact"), profile(List.of("list"), false, false));

        assertThat(names(visible)).containsExactly("list_contacts");
    }

    @Test
    void readOnlyStripsWritesFromTheList() {
        List<ChatTypes.ToolSpec> visible = FacadeToolGate.visible(
                tools("get_contact", "create_contact"), server(), profile(List.of(), true, null));

        assertThat(names(visible)).containsExactly("get_contact");
    }

    @Test
    void dryRunKeepsWritesVisibleTheWorkerPlansWithThem() {
        List<ChatTypes.ToolSpec> visible = FacadeToolGate.visible(
                tools("get_contact", "create_contact"), server(), profile(List.of(), false, true));

        assertThat(names(visible)).containsExactly("get_contact", "create_contact");
    }

    // ------------------------------------------------------------ call decisions

    @Test
    void aReadAlwaysExecutes() {
        assertThat(FacadeToolGate.decide("get_contact", profile(List.of(), true, true)))
                .isEqualTo(FacadeToolGate.CallDecision.EXECUTE);
    }

    @Test
    void aWriteUnderReadOnlyIsBlocked() {
        assertThat(FacadeToolGate.decide("create_contact", profile(List.of(), true, false)))
                .isEqualTo(FacadeToolGate.CallDecision.BLOCK);
    }

    @Test
    void aWriteUnderDryRunIsSimulated() {
        assertThat(FacadeToolGate.decide("create_contact", profile(List.of(), false, true)))
                .isEqualTo(FacadeToolGate.CallDecision.DRY_RUN);
    }

    @Test
    void dryRunIsTheDefaultWhenTheProfileNeverSaysOtherwise() {
        // null dryRun means ON: writes stay behind the guard until someone deliberately clears it.
        assertThat(FacadeToolGate.decide("create_contact", profile(List.of(), false, null)))
                .isEqualTo(FacadeToolGate.CallDecision.DRY_RUN);
    }

    @Test
    void aWriteOnlyExecutesWhenDryRunWasDeliberatelyCleared() {
        assertThat(FacadeToolGate.decide("create_contact", profile(List.of(), false, false)))
                .isEqualTo(FacadeToolGate.CallDecision.EXECUTE);
    }

    @Test
    void theAllowlistIsCheckedOnCallsNotJustListings() {
        McpServerSpec server = server("contact");
        FacadeProfile profile = profile(List.of("list"), false, false);

        assertThat(FacadeToolGate.allowlisted("list_contacts", server, profile)).isTrue();
        assertThat(FacadeToolGate.allowlisted("list_invoices", server, profile)).isFalse();
        assertThat(FacadeToolGate.allowlisted("create_contact", server, profile)).isFalse();
    }
}
