package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.mail.MailHandOffSpec;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What the compiler makes of a Send mail node: a hand-off, collected beside the flow hand-offs,
 * and refused when it is wired to nothing — because a box on the canvas is a promise.
 */
class MailCompilerTest {

    private static final FlowCompiler COMPILER = new FlowCompiler();

    private static FlowNode node(String id, String type, String role, Map<String, Object> data) {
        return new FlowNode(id, type, role, data);
    }

    private static FlowGraph flow(List<FlowNode> nodes, List<FlowEdge> edges) {
        return new FlowGraph("parent", "Parent", "local", nodes, edges,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static List<FlowNode> base(FlowNode... extra) {
        List<FlowNode> nodes = new java.util.ArrayList<>(List.of(
                node("in-1", "input", null, Map.of("mode", "manual")),
                node("agent-1", "agent", "coordinator",
                        Map.of("name", "Lead", "systemPrompt", "Lead the work."))));
        nodes.addAll(List.of(extra));
        return nodes;
    }

    private static FlowNode mail() {
        return node("mail-1", "mail", null, Map.of(
                "label", "Tell Gerard", "to", "gerard@example.com", "subject", "{{flow}} — {{status}}",
                "smtpHost", "smtp.gmail.com", "smtpPort", 465, "smtpStarttls", false,
                "smtpUsername", "bot@gmail.com", "from", "bot@gmail.com", "credentialId", "cred_1"));
    }

    @Test
    void a_mail_node_wired_out_of_an_agent_compiles_into_the_mail_hand_offs() {
        FlowGraph graph = flow(base(mail()),
                List.of(new FlowEdge("e1", "in-1", "agent-1"), new FlowEdge("e2", "agent-1", "mail-1")));

        CompiledFlow compiled = COMPILER.compile(graph);

        assertThat(compiled.afterFlows()).isEmpty();
        assertThat(compiled.afterMails()).hasSize(1);
        MailHandOffSpec spec = compiled.afterMails().get(0);
        assertThat(spec.nodeId()).isEqualTo("mail-1");
        assertThat(spec.label()).isEqualTo("Tell Gerard");
        assertThat(spec.to()).isEqualTo("gerard@example.com");
        assertThat(spec.subject()).isEqualTo("{{flow}} — {{status}}");
        assertThat(spec.smtpHost()).isEqualTo("smtp.gmail.com");
        assertThat(spec.smtpPort()).isEqualTo(465);
        assertThat(spec.smtpStarttls()).isFalse();
        assertThat(spec.credentialId()).isEqualTo("cred_1");
        // The node never carries the secret, so the spec has nowhere to put one.
        assertThat(spec.account("pw").password()).isEqualTo("pw");
    }

    @Test
    void a_mail_node_on_a_second_output_or_behind_a_gate_is_still_a_hand_off() {
        FlowNode gate = node("if-1", "condition", null, Map.of("test", "not_empty"));
        FlowGraph graph = flow(base(mail(), gate),
                List.of(new FlowEdge("e1", "in-1", "agent-1"),
                        new FlowEdge("e2", "agent-1", "if-1", FlowEdge.ERROR),
                        new FlowEdge("e3", "if-1", "mail-1")));

        CompiledFlow compiled = COMPILER.compile(graph);

        // Which output it hangs off is read from the graph when the run ends, not compiled: the
        // compiler only has to see through the gate to know the node is wired at all.
        assertThat(compiled.afterMails()).extracting(MailHandOffSpec::nodeId).containsExactly("mail-1");
    }

    @Test
    void a_mail_node_wired_to_nothing_is_refused_rather_than_ignored() {
        FlowGraph graph = flow(base(mail()), List.of(new FlowEdge("e1", "in-1", "agent-1")));

        assertThat(catchThrowable(() -> COMPILER.compile(graph)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tell Gerard")
                .hasMessageContaining("would never send");
    }

    @Test
    void the_port_follows_the_security_choice_when_none_is_typed() {
        MailHandOffSpec starttls = MailHandOffSpec.from(node("m", "mail", null, Map.of("smtpStarttls", true)));
        MailHandOffSpec tls = MailHandOffSpec.from(node("m", "mail", null, Map.of("smtpStarttls", false)));

        assertThat(starttls.smtpPort()).isEqualTo(587);
        assertThat(tls.smtpPort()).isEqualTo(465);
        // STARTTLS on 587 is what nearly every provider documents, so it is what an untouched
        // node does.
        assertThat(MailHandOffSpec.from(node("m", "mail", null, Map.of())).smtpStarttls()).isTrue();
    }

    @Test
    void the_shapes_every_earlier_caller_used_still_build_with_no_mail_hand_offs() {
        AgentSpec c = new AgentSpec();
        assertThat(new CompiledFlow(c, List.of()).afterMails()).isEmpty();
        assertThat(new CompiledFlow(c, List.of(), null, null, List.of()).afterMails()).isEmpty();
        assertThat(new CompiledFlow(c, List.of(), null, null, List.of(), null).afterMails()).isEmpty();
    }
}
