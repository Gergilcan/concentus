package com.concentus.service;

import com.concentus.llm.ChatTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Choosing which of a server's tools an agent is handed.
 *
 * <p>Written after a real failure: Holded's MCP exposes <b>338</b> tools, every one a JSON schema
 * in the prompt. That overflowed the self-hosted model's context, the model server truncated
 * without complaining, and the model then answered that it only had time-tracking and webhook
 * tools — the tail of the list, which was all that survived. Silent, and it looked like the model
 * was confused rather than starved.
 */
class McpToolSelectionTest {

    private static ChatTypes.ToolSpec tool(String name) {
        return new ChatTypes.ToolSpec(name, "", new ObjectMapper().createObjectNode());
    }

    private static final List<ChatTypes.ToolSpec> OFFERED = List.of(
            tool("create_contact"), tool("list_contacts"), tool("delete_contact"),
            tool("create_estimate"), tool("list_employee_times"), tool("list_webhooks"));

    private static List<String> names(List<ChatTypes.ToolSpec> tools) {
        return tools.stream().map(ChatTypes.ToolSpec::name).toList();
    }

    @Test
    void noFilterKeepsEverything() {
        assertThat(LocalModelAgentExecutor.selectTools(OFFERED, List.of())).isEqualTo(OFFERED);
        assertThat(LocalModelAgentExecutor.selectTools(OFFERED, null)).isEqualTo(OFFERED);
    }

    @Test
    void aSubstringSelectsTheWholeFamily() {
        // The point of substrings: nobody should have to transcribe eleven exact tool names.
        List<ChatTypes.ToolSpec> selected =
                LocalModelAgentExecutor.selectTools(OFFERED, List.of("contact"));

        assertThat(names(selected))
                .containsExactly("create_contact", "list_contacts", "delete_contact");
    }

    @Test
    void severalFiltersUnion() {
        List<ChatTypes.ToolSpec> selected =
                LocalModelAgentExecutor.selectTools(OFFERED, List.of("contact", "estimate"));

        assertThat(names(selected)).contains("create_estimate").hasSize(4);
    }

    @Test
    void matchingIgnoresCase() {
        assertThat(LocalModelAgentExecutor.selectTools(OFFERED, List.of("CONTACT"))).hasSize(3);
    }

    @Test
    void blankEntriesAreIgnoredRatherThanMatchingEverything() {
        // "contact, " leaves an empty entry, and an empty substring matches every name — which
        // would silently undo the filter someone just typed.
        assertThat(LocalModelAgentExecutor.selectTools(OFFERED, List.of("contact", "  "))).hasSize(3);
    }

    @Test
    void aFilterOfOnlyBlanksIsTreatedAsNoFilter() {
        assertThat(LocalModelAgentExecutor.selectTools(OFFERED, List.of("", " "))).isEqualTo(OFFERED);
    }

    @Test
    void aFilterThatMatchesNothingYieldsNothingRatherThanEverything() {
        // Failing open would hand back all 338 and reproduce the original failure.
        assertThat(LocalModelAgentExecutor.selectTools(OFFERED, List.of("payroll"))).isEmpty();
    }

    @Test
    void theOfferedOrderIsPreserved() {
        // The cap takes a prefix of this list, so the order decides what survives.
        assertThat(names(LocalModelAgentExecutor.selectTools(OFFERED, List.of("list"))))
                .containsExactly("list_contacts", "list_employee_times", "list_webhooks");
    }
}
