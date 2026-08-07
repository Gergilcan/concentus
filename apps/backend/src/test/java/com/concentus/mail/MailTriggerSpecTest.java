package com.concentus.mail;

import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.TriggerSpec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a mail trigger off an Input node.
 *
 * <p>The defaults carry most of the weight here: a half-configured node has to be recognisable as
 * half-configured, and a condition left blank has to mean "don't filter", not "match nothing".
 */
class MailTriggerSpecTest {

    private static FlowGraph flowWith(Map<String, Object> inputData) {
        Map<String, Object> data = new HashMap<>(inputData);
        data.putIfAbsent("mode", "mail");
        return new FlowGraph("f1", "Flow", "local", List.of(new FlowNode("in1", "input", null, data)),
                List.of(), true, List.of(), false, null);
    }

    private static Map<String, Object> connected() {
        Map<String, Object> d = new HashMap<>();
        d.put("mailHost", "imap.example.com");
        d.put("mailUsername", "buzon@example.com");
        d.put("mailCredentialId", "cred_abc123");
        d.put("mailFolder", "Presupuestos");
        return d;
    }

    @Test
    void theModeIsRecognisedAsAMailTrigger() {
        assertThat(TriggerSpec.from(flowWith(connected())).mail()).isTrue();
        assertThat(TriggerSpec.from(flowWith(connected())).webhook()).isFalse();
    }

    @Test
    void connectionDetailsAreReadFromTheNode() {
        MailTriggerSpec spec = MailTriggerSpec.from(flowWith(connected()));

        assertThat(spec.host()).isEqualTo("imap.example.com");
        assertThat(spec.username()).isEqualTo("buzon@example.com");
        assertThat(spec.folder()).isEqualTo("Presupuestos");
        assertThat(spec.isConfigured()).isTrue();
    }

    @Test
    void theNodeStoresACredentialIdNotThePassword() {
        // A reference, not the value: every flow save snapshots the JSON into version history and
        // duplicating a flow copies its nodes, so a secret here would fan out into both.
        MailTriggerSpec spec = MailTriggerSpec.from(flowWith(connected()));

        assertThat(spec.credentialId()).isEqualTo("cred_abc123");
        assertThat(spec.toString()).doesNotContain("hunter2");
    }

    @Test
    void tlsIsTheDefaultAndPicksThe993Port() {
        MailTriggerSpec spec = MailTriggerSpec.from(flowWith(connected()));

        assertThat(spec.ssl()).isTrue();
        assertThat(spec.port()).isEqualTo(MailTriggerSpec.DEFAULT_SSL_PORT);
    }

    @Test
    void turningOffTlsFallsBackToThePlainPort() {
        Map<String, Object> d = connected();
        d.put("mailSsl", false);

        MailTriggerSpec spec = MailTriggerSpec.from(flowWith(d));

        assertThat(spec.port()).isEqualTo(MailTriggerSpec.DEFAULT_PLAIN_PORT);
    }

    @Test
    void anExplicitPortWinsOverTheDefault() {
        Map<String, Object> d = connected();
        d.put("mailPort", 1993);

        assertThat(MailTriggerSpec.from(flowWith(d)).port()).isEqualTo(1993);
    }

    @Test
    void blankConditionsMeanMatchEverythingInTheFolder() {
        MailTriggerSpec spec = MailTriggerSpec.from(flowWith(connected()));

        assertThat(spec.from()).isEmpty();
        assertThat(spec.subjectContains()).isEmpty();
        assertThat(spec.flaggedOnly()).isFalse();
        assertThat(spec.withAttachmentsOnly()).isFalse();
        // Unread-only is the one condition that defaults ON: without it, the first poll of an
        // existing folder would start a run for every message ever received.
        assertThat(spec.unseenOnly()).isTrue();
    }

    @Test
    void conditionsAreReadWhenSet() {
        Map<String, Object> d = connected();
        d.put("mailFrom", "@cliente.com");
        d.put("mailSubjectContains", "presupuesto");
        d.put("mailFlaggedOnly", true);
        d.put("mailWithAttachmentsOnly", true);

        MailTriggerSpec spec = MailTriggerSpec.from(flowWith(d));

        assertThat(spec.from()).isEqualTo("@cliente.com");
        assertThat(spec.subjectContains()).isEqualTo("presupuesto");
        assertThat(spec.flaggedOnly()).isTrue();
        assertThat(spec.withAttachmentsOnly()).isTrue();
    }

    @Test
    void booleansSurviveBeingStoredAsStrings() {
        // A hand-edited or imported flow carries "true" rather than true; reading that as false
        // would silently disable a condition someone thought they had set.
        Map<String, Object> d = connected();
        d.put("mailFlaggedOnly", "true");
        d.put("mailUnseenOnly", "false");

        MailTriggerSpec spec = MailTriggerSpec.from(flowWith(d));

        assertThat(spec.flaggedOnly()).isTrue();
        assertThat(spec.unseenOnly()).isFalse();
    }

    @Test
    void theFolderDefaultsToInbox() {
        Map<String, Object> d = connected();
        d.remove("mailFolder");

        assertThat(MailTriggerSpec.from(flowWith(d)).folder()).isEqualTo("INBOX");
    }

    @Test
    void aPollIntervalIsFloored() {
        // Anything under 15s is a mistake, and a provider would start throttling.
        Map<String, Object> d = connected();
        d.put("mailPollSeconds", 1);

        assertThat(MailTriggerSpec.from(flowWith(d)).pollSeconds()).isEqualTo(15);
    }

    @Test
    void theBatchCapIsAtLeastOne() {
        Map<String, Object> d = connected();
        d.put("mailMaxPerPoll", 0);

        assertThat(MailTriggerSpec.from(flowWith(d)).maxPerPoll()).isEqualTo(1);
    }

    @Test
    void aHalfConfiguredNodeNamesWhatIsMissing() {
        // Reported rather than silent: a mail trigger that does nothing is indistinguishable
        // from a mailbox with no new mail.
        Map<String, Object> d = connected();
        d.remove("mailHost");
        d.remove("mailCredentialId");

        MailTriggerSpec spec = MailTriggerSpec.from(flowWith(d));

        assertThat(spec.isConfigured()).isFalse();
        assertThat(spec.missingFields()).contains("host").contains("credential");
    }

    @Test
    void movingIsOptional() {
        assertThat(MailTriggerSpec.from(flowWith(connected())).movesProcessedMail()).isFalse();

        Map<String, Object> d = connected();
        d.put("mailMoveToFolder", "Presupuestos/Procesados");
        assertThat(MailTriggerSpec.from(flowWith(d)).movesProcessedMail()).isTrue();
    }

    @Test
    void aFlowWithNoInputNodeYieldsAnUnconfiguredSpec() {
        FlowGraph noInput = new FlowGraph("f1", "Flow", "local", List.of(), List.of(), true,
                List.of(), false, null);

        assertThat(MailTriggerSpec.from(noInput).isConfigured()).isFalse();
    }
}
