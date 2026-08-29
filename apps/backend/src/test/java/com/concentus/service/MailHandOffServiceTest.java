package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.mail.MailAccount;
import com.concentus.mail.MailHandOffSpec;
import com.concentus.mail.MailSender;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.concentus.secrets.CredentialStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mail at the end of a run, on the same wires a flow hand-off reads.
 *
 * <p>The cases mirror {@link SubflowServiceTest}'s per-output ones on purpose: the two families
 * must agree on WHEN a branch fires, or a mail and a flow drawn on the same output would tell two
 * different stories about the same run.
 */
class MailHandOffServiceTest {

    /** Records every submission, and can be told to refuse them all. */
    private static final class FakeSender implements MailSender {
        record Sent(MailAccount account, String to, String subject, String body) {
        }

        final List<Sent> sent = new ArrayList<>();
        String refuse;

        @Override
        public void send(MailAccount account, String to, String subject, String body) {
            if (refuse != null) throw new MailSendException(refuse);
            sent.add(new Sent(account, to, subject, body));
        }
    }

    private final FakeSender sender = new FakeSender();
    private final CredentialStore credentials = mock(CredentialStore.class);

    private MailHandOffService service() {
        when(credentials.reveal("default", "cred_mail")).thenReturn(Optional.of("app-password"));
        return new MailHandOffService(sender, credentials);
    }

    private static Map<String, Object> mailData(String subject) {
        return Map.of("label", "Mail me", "to", "gerard@example.com, ops@example.com",
                "subject", subject, "smtpHost", "smtp.example.com", "from", "bot@example.com",
                "credentialId", "cred_mail");
    }

    private static FlowNode mailNode(String subject) {
        return new FlowNode("m1", "mail", null, mailData(subject));
    }

    private static AgentSpec agent(String nodeId, String name) {
        AgentSpec s = new AgentSpec();
        s.nodeId = nodeId;
        s.name = name;
        return s;
    }

    /**
     * coordinator a → workers w1, w2 → verifier v; and one mail hanging off {@code handle} of
     * {@code source}, with whatever gates sit in between.
     */
    private static FlowGraph graph(String source, String handle, String subject, FlowNode... gates) {
        List<FlowNode> nodes = new ArrayList<>(List.of(
                new FlowNode("a", "agent", "coordinator", Map.of()),
                new FlowNode("w1", "agent", "subagent", Map.of()),
                new FlowNode("w2", "agent", "subagent", Map.of()),
                new FlowNode("v", "verifier", null, Map.of()),
                mailNode(subject)));
        nodes.addAll(List.of(gates));
        List<FlowEdge> edges = new ArrayList<>(List.of(
                new FlowEdge("e1", "a", "w1"),
                new FlowEdge("e2", "a", "w2")));
        String previous = source;
        String previousHandle = handle;
        int i = 3;
        for (FlowNode gate : gates) {
            edges.add(new FlowEdge("e" + i++, previous, gate.id(), previousHandle));
            previous = gate.id();
            previousHandle = null;
        }
        edges.add(new FlowEdge("e" + i, previous, "m1", previousHandle));
        return new FlowGraph("flow_parent", "Ads campaign", "managed", nodes, edges,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static FlowGraph graph(String source, String handle) {
        return graph(source, handle, "{{flow}}: {{status}}");
    }

    private static AgentRun run(String status, String subject) {
        AgentRun run = new AgentRun("run-1", "flow_parent", "Ads campaign", "managed");
        run.organizationId = "default";
        run.status = status;
        run.compiled = new CompiledFlow(agent("a", "Planner"),
                List.of(agent("w1", "Ads writer"), agent("w2", "Ads reviewer")),
                null, agent("v", "Judge"), List.of(), List.of(MailHandOffSpec.from(mailNode(subject))));
        return run;
    }

    private static AgentRun run(String status) {
        return run(status, "{{flow}}: {{status}}");
    }

    private static void rejected(AgentRun run, String worker, String label, String reason) {
        NodeExec exec = run.nodeExec(worker, "agent", label);
        exec.status = "passed";
        exec.verdict = "rejected";
        exec.verdictReason = reason;
        exec.output = "CTR is 12%.";
    }

    // ------------------------------------------------------------ the three families

    @Test
    void the_verifiers_rejected_output_mails_the_whole_report_with_the_placeholders_filled_in() {
        AgentRun run = run("COMPLETED", "[{{flow}}] verifier said {{status}}");
        run.restoreEvents(List.of(RunEvent.of("agent_message", "Merged.")));
        run.nodeExec("w1", "agent", "Ads writer").verdict = "accepted";
        rejected(run, "w2", "Ads reviewer", "Cites a CTR that appears in no file.");

        service().handOffAfter(run, graph("v", FlowEdge.REJECTED, "[{{flow}}] verifier said {{status}}"));

        assertThat(sender.sent).hasSize(1);
        FakeSender.Sent mail = sender.sent.get(0);
        assertThat(mail.to()).isEqualTo("gerard@example.com, ops@example.com");
        assertThat(mail.subject()).isEqualTo("[Ads campaign] verifier said rejected");
        assertThat(mail.body()).startsWith("# Verification report — Ads campaign")
                .contains("## ✖ Ads reviewer — REJECTED")
                .contains("Cites a CTR that appears in no file.");
        // The account is resolved from the node plus the stored secret, and only the secret is
        // ever fetched from the store — nothing on the node is a password.
        assertThat(mail.account().host()).isEqualTo("smtp.example.com");
        assertThat(mail.account().port()).isEqualTo(MailHandOffSpec.DEFAULT_STARTTLS_PORT);
        assertThat(mail.account().username()).isEqualTo("bot@example.com");
        assertThat(mail.account().password()).isEqualTo("app-password");
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("Mail 'Mail me' sent to gerard@example.com, ops@example.com"));
    }

    @Test
    void the_rejected_output_stays_quiet_when_everything_was_accepted() {
        AgentRun run = run("COMPLETED");
        run.restoreEvents(List.of(RunEvent.of("agent_message", "Merged.")));
        run.nodeExec("w1", "agent", "Ads writer").verdict = "accepted";
        run.nodeExec("w2", "agent", "Ads reviewer").verdict = "accepted";

        service().handOffAfter(run, graph("v", FlowEdge.REJECTED));

        assertThat(sender.sent).isEmpty();
    }

    @Test
    void the_error_output_of_a_block_mails_that_blocks_failure_even_though_the_run_completed() {
        AgentRun run = run("COMPLETED");
        run.restoreEvents(List.of(RunEvent.of("agent_message", "Merged.")));
        NodeExec w1 = run.nodeExec("w1", "agent", "Ads writer");
        w1.status = "failed";
        w1.error = "timed out after 10 minutes";

        service().handOffAfter(run, graph("w1", FlowEdge.ERROR));

        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).subject()).isEqualTo("Ads campaign: failed");
        assertThat(sender.sent.get(0).body()).startsWith("Ads writer failed: timed out after 10 minutes");
    }

    @Test
    void the_error_output_of_a_block_does_not_fire_for_another_blocks_failure() {
        AgentRun run = run("ERROR");
        run.error = "timed out";
        NodeExec w2 = run.nodeExec("w2", "agent", "Ads reviewer");
        w2.status = "failed";
        w2.error = "timed out";

        service().handOffAfter(run, graph("w1", FlowEdge.ERROR));

        assertThat(sender.sent).isEmpty();
        assertThat(run.status).isEqualTo("ERROR");
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("'Ads reviewer' failed and no mail is wired to its error output"));
    }

    @Test
    void a_failure_nobody_pinned_on_a_block_mails_from_the_coordinators_error_output() {
        AgentRun run = run("ERROR");
        run.error = "Every worker failed. The combined report lists each reason.";

        service().handOffAfter(run, graph("a", FlowEdge.ERROR));

        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).body()).startsWith("Every worker failed.");
        // Mailed, therefore handled: somebody drew "tell me when this breaks", and it did.
        assertThat(run.status).isEqualTo("COMPLETED");
        assertThat(run.failureHandled).isTrue();
    }

    @Test
    void a_run_that_completed_does_not_send_the_error_mail() {
        AgentRun run = run("COMPLETED");
        run.restoreEvents(List.of(RunEvent.of("agent_message", "Done.")));

        service().handOffAfter(run, graph("a", FlowEdge.ERROR));

        // The "it broke" mail on a run where nothing broke would be the worst kind of noise: the
        // one that trains people to ignore the real one.
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void the_main_output_mails_the_final_answer_on_success_and_nothing_on_failure() {
        AgentRun ok = run("COMPLETED");
        ok.restoreEvents(List.of(RunEvent.of("agent_message", "Eleven leads this week.")));
        service().handOffAfter(ok, graph("a", null));
        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).subject()).isEqualTo("Ads campaign: completed");
        assertThat(sender.sent.get(0).body()).isEqualTo("Eleven leads this week.");

        sender.sent.clear();
        AgentRun failed = run("ERROR");
        failed.error = "budget";
        service().handOffAfter(failed, graph("a", null));
        assertThat(sender.sent).isEmpty();
        assertThat(failed.status).isEqualTo("ERROR");
    }

    // ------------------------------------------------------------ once, and honestly

    @Test
    void mails_go_out_once_per_run_however_many_turns_it_has() {
        AgentRun run = run("COMPLETED");
        run.restoreEvents(List.of(RunEvent.of("agent_message", "Done.")));
        MailHandOffService service = service();

        service.handOffAfter(run, graph("a", null));
        service.handOffAfter(run, graph("a", null));

        assertThat(sender.sent).hasSize(1);
    }

    @Test
    void a_mail_the_server_refused_leaves_the_failure_unhandled_and_says_why() {
        AgentRun run = run("ERROR");
        run.error = "Every worker failed.";
        sender.refuse = "SMTP submission to smtp.example.com failed: 535 Authentication failed";

        service().handOffAfter(run, graph("a", FlowEdge.ERROR));

        assertThat(sender.sent).isEmpty();
        // Nobody was told, so nothing was handled: the run stays red where someone will see it.
        assertThat(run.status).isEqualTo("ERROR");
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("Mail 'Mail me' was not sent: SMTP submission")
                        .contains("535 Authentication failed"));
    }

    @Test
    void a_credential_that_no_longer_exists_is_said_in_the_log_rather_than_thrown() {
        when(credentials.reveal(anyString(), anyString())).thenReturn(Optional.empty());
        MailHandOffService service = new MailHandOffService(sender, credentials);
        AgentRun run = run("COMPLETED");
        run.restoreEvents(List.of(RunEvent.of("agent_message", "Done.")));

        service.handOffAfter(run, graph("a", null));

        assertThat(sender.sent).isEmpty();
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("its credential no longer exists"));
    }

    @Test
    void a_failure_a_flow_hand_off_already_handled_still_reaches_the_mail_on_the_same_output() {
        // The flow family runs first and turns the run green. A mail on the error output was
        // drawn for the failure, not for the colour, and must still go out — and the main output
        // must NOT read the recovered run as a success with an answer to mail.
        AgentRun run = run("COMPLETED");
        run.failureHandled = true;
        run.error = "Every worker failed.";
        run.restoreEvents(List.of(RunEvent.of("agent_message", "partial")));

        service().handOffAfter(run, graph("a", FlowEdge.ERROR));
        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).subject()).isEqualTo("Ads campaign: failed");

        sender.sent.clear();
        AgentRun recovered = run("COMPLETED");
        recovered.failureHandled = true;
        service().handOffAfter(recovered, graph("a", null));
        assertThat(sender.sent).isEmpty();
    }

    // ------------------------------------------------------------ gates on the wire

    @Test
    void a_condition_in_front_of_the_mail_decides_whether_it_goes_out() {
        FlowNode gate = new FlowNode("if-1", "condition", null,
                Map.of("label", "mentions CTR", "test", "contains", "value", "CTR"));
        AgentRun run = run("COMPLETED");
        run.restoreEvents(List.of(RunEvent.of("agent_message", "Nothing to report.")));

        service().handOffAfter(run, graph("a", null, "{{flow}}: {{status}}", gate));

        assertThat(sender.sent).isEmpty();
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("Mail 'Mail me': 'mentions CTR' did not pass"));
    }

    @Test
    void a_for_each_in_front_of_the_mail_sends_one_per_item() {
        FlowNode each = new FlowNode("each-1", "foreach", null, Map.of("label", "per lead", "source", "lines"));
        AgentRun run = run("COMPLETED");
        run.restoreEvents(List.of(RunEvent.of("agent_message", "- Acme\n- Globex")));

        service().handOffAfter(run, graph("a", null, "{{flow}}: {{status}}", each));

        assertThat(sender.sent).extracting(FakeSender.Sent::body).containsExactly("Acme", "Globex");
    }

    @Test
    void a_node_missing_its_account_is_reported_instead_of_attempted() {
        AgentRun run = new AgentRun("run-1", "flow_parent", "Ads campaign", "managed");
        run.organizationId = "default";
        run.status = "COMPLETED";
        run.restoreEvents(List.of(RunEvent.of("agent_message", "Done.")));
        FlowNode bare = new FlowNode("m1", "mail", null, Map.of("label", "Mail me", "to", "gerard@example.com"));
        run.compiled = new CompiledFlow(agent("a", "Planner"), List.of(), null, null, List.of(),
                List.of(MailHandOffSpec.from(bare)));

        service().handOffAfter(run, graph("a", null));

        assertThat(sender.sent).isEmpty();
        assertThat(run.bufferedEvents()).anySatisfy(e ->
                assertThat(e.text()).contains("the node has no SMTP host, from address, credential"));
    }
}
