package com.concentus.service;

import com.concentus.integration.Redact;
import com.concentus.mail.MailAccount;
import com.concentus.mail.MailHandOffSpec;
import com.concentus.mail.MailSender;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunEvent;
import com.concentus.secrets.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Send mail nodes drawn terminal, fired once the run they belong to has finished.
 *
 * <p>The sibling of {@link SubflowService#handOffAfter}: the same wires, the same three families,
 * the same payloads — a mail on the verifier's rejected output receives the verification report a
 * flow on that output would. What differs is only what happens to the text at the end: it goes
 * into a mailbox instead of into another flow's prompt. That is the whole reason this node
 * exists. "When the verifier rejects the Ads agents, email me the report" used to need a second
 * flow whose one agent was told to send mail, and an agent told to do something is a request.
 *
 * <p>The rule is kept in step with the sibling by hand rather than shared, because the two
 * diverge in what a refusal means — a flow that cannot start ends its branch, a mail that cannot
 * be sent is one line in the log — and folding them into one walk would make each carry the
 * other's exceptions.
 */
@Service
public class MailHandOffService {

    private static final Logger log = LoggerFactory.getLogger(MailHandOffService.class);

    private final MailSender sender;
    private final CredentialStore credentials;

    public MailHandOffService(MailSender sender, CredentialStore credentials) {
        this.sender = sender;
        this.credentials = credentials;
    }

    /**
     * Sends whatever the run's mail nodes are wired to receive, once per run.
     *
     * <p>A null graph means no gates and every node on the main output — what a flow drawn before
     * outputs had names does. Called after the flow hand-offs on purpose: a failure they handled
     * has already turned the run green, and {@link AgentRun#failureHandled} is how this walk still
     * sees the failure that a mail on the error output was drawn for.
     */
    public void handOffAfter(AgentRun run, FlowGraph graph) {
        if (run.compiled == null || run.compiled.afterMails().isEmpty()) return;
        if (run.mailHandOffsFired) return;

        boolean failed = !"COMPLETED".equals(run.status) || run.failureHandled;
        boolean unattributed = failed && run.failedNodeLabel() == null;
        List<MailHandOffSpec> toSend = new ArrayList<>();
        Map<String, String> payloads = new LinkedHashMap<>();
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (MailHandOffSpec drawn : run.compiled.afterMails()) {
            FlowGates.Origin origin = graph == null
                    ? new FlowGates.Origin(null, null)
                    : FlowGates.originOf(graph, drawn.nodeId());
            String payload = null;
            String outcome = null;
            if (origin.onMain()) {
                if (!failed) {
                    payload = run.finalOutput() == null ? "" : run.finalOutput();
                    outcome = "completed";
                }
            } else if (origin.is(FlowEdge.ERROR)) {
                boolean blockFailed = run.nodeFailed(origin.sourceId())
                        || (unattributed && BranchPayloads.isCoordinator(run, graph, origin.sourceId()));
                if (blockFailed) {
                    payload = BranchPayloads.errorOf(run, origin.sourceId());
                    outcome = "failed";
                }
            } else if (origin.is(FlowEdge.REJECTED)) {
                if (run.anyRejected()) {
                    payload = BranchPayloads.verificationReport(run);
                    outcome = "rejected";
                }
            }
            if (payload == null) continue;
            toSend.add(drawn);
            payloads.put(drawn.nodeId(), payload);
            outcomes.put(drawn.nodeId(), outcome);
        }

        if (toSend.isEmpty()) {
            if (failed && !run.failureHandled) {
                String where = run.failedNodeLabel();
                run.emit(RunEvent.of("system", (where == null
                        ? "This run did not complete, and no mail is wired to its coordinator's error output"
                        : "'" + where + "' failed and no mail is wired to its error output")
                        + ", so none of its " + run.compiled.afterMails().size()
                        + " mail(s) were sent."));
            }
            return;
        }

        run.mailHandOffsFired = true;
        boolean recoverySent = false;
        for (MailHandOffSpec drawn : toSend) {
            String output = payloads.get(drawn.nodeId());
            FlowGates.Decision decision = graph == null
                    ? new FlowGates.Decision(List.of(output), null)
                    : FlowGates.decide(graph, drawn.nodeId(), output);
            if (decision.reason() != null) {
                run.emit(RunEvent.of("system", "Mail '" + drawn.label() + "': " + decision.reason()));
            }
            if (!decision.fires()) continue;

            if (!drawn.isConfigured()) {
                run.emit(RunEvent.of("system", "Mail '" + drawn.label() + "' was not sent: the node has no "
                        + drawn.missingFields() + "."));
                continue;
            }
            String password = credentials.reveal(run.organizationId, drawn.credentialId())
                    .orElse(null);
            if (password == null) {
                run.emit(RunEvent.of("system", "Mail '" + drawn.label() + "' was not sent: its credential "
                        + "no longer exists. Pick one again on the node."));
                continue;
            }
            MailAccount account = drawn.account(password);
            String subject = subjectOf(drawn, run, outcomes.get(drawn.nodeId()));

            boolean anySent = false;
            for (String body : decision.prompts()) {
                try {
                    sender.send(account, drawn.to(), subject, body);
                    anySent = true;
                    run.emit(RunEvent.of("system", "Mail '" + drawn.label() + "' sent to "
                            + drawn.to() + " — \"" + subject + "\"."));
                } catch (RuntimeException e) {
                    String reason = Redact.secrets(e.getMessage() == null ? e.toString() : e.getMessage());
                    log.warn("Run {}: mail '{}' was not sent — {}", run.id, drawn.label(), reason);
                    run.emit(RunEvent.of("system", "Mail '" + drawn.label() + "' was not sent: " + reason));
                    // One refusal ends the node: a server that rejected the login or the sender
                    // will reject the next item for the same reason.
                    break;
                }
            }
            if (anySent && !"completed".equals(outcomes.get(drawn.nodeId()))) recoverySent = true;
        }

        // Handled, on the same terms as a flow on the same wire: somebody drew "mail me when this
        // goes wrong", and it went wrong, and they were mailed. Only when the mail actually left —
        // a failure whose only handler could not be reached is still an unattended failure.
        if (failed && recoverySent && !"COMPLETED".equals(run.status)) {
            run.failureHandled = true;
            run.status = "COMPLETED";
            run.emit(RunEvent.of("system",
                    "The failure was handled by the mail wired to the failing block's second output, "
                            + "so this run is reported as completed. The failure itself is above."));
        }
    }

    /**
     * The subject with its placeholders filled in. Two, and only two, because a subject line is
     * where a person decides whether to open the mail: the flow it came from, and whether the
     * news is good.
     */
    static String subjectOf(MailHandOffSpec drawn, AgentRun run, String outcome) {
        String flow = run.flowName == null || run.flowName.isBlank() ? run.flowId : run.flowName;
        String subject = drawn.subject().isBlank() ? "{{flow}}: {{status}}" : drawn.subject();
        return subject.replace("{{flow}}", flow == null ? "" : flow)
                .replace("{{status}}", outcome == null ? "" : outcome);
    }
}
