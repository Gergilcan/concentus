package com.concentus.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redaction, which is the difference between a diagnosable log and a credential leak.
 *
 * <p>Integration code logs provider responses freely, and those responses carry bearer tokens,
 * client secrets and — for a mail workflow — the customer's own address. The tests assert both
 * halves: the secret is gone, and enough context survives to debug with.
 */
class RedactTest {

    @Test
    void bearerTokensAreRemovedFromAnywhereInTheText() {
        String text = "GET failed, sent Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6IngifQ.abc.def";

        String redacted = Redact.secrets(text);

        assertThat(redacted).doesNotContain("eyJhbGci");
        assertThat(redacted).contains("Bearer [redacted]");
        // The surrounding context has to survive, or the log line is useless.
        assertThat(redacted).contains("GET failed");
    }

    @Test
    void tokenAndSecretFieldsAreRemovedFromJson() {
        String json = """
            {"access_token":"abc.def.ghi","refresh_token":"rrr","client_secret":"sss",
             "clientState":"the-webhook-secret","error":"invalid_client"}
            """;

        String redacted = Redact.secrets(json);

        assertThat(redacted).doesNotContain("abc.def.ghi").doesNotContain("rrr").doesNotContain("sss");
        assertThat(redacted).doesNotContain("the-webhook-secret");
        // The error code is what an operator needs; it is not a secret.
        assertThat(redacted).contains("invalid_client");
    }

    @Test
    void bareJwtsArePickedUpEvenWithoutAFieldName() {
        String text = "assertion eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhYmMifQ.signature was rejected";

        assertThat(Redact.secrets(text)).contains("[redacted-jwt]").doesNotContain("eyJzdWIi");
    }

    @Test
    void personalDataRedactionMasksEmailAddresses() {
        String text = "Processing message from ana.garcia@acme.example about a quote";

        String redacted = Redact.personalData(text);

        assertThat(redacted).doesNotContain("ana.garcia@acme.example");
        // Still recognisable to an operator matching it against a mailbox, without storing it.
        assertThat(redacted).contains("@acme.example");
    }

    @Test
    void aShortLocalPartIsStillMasked() {
        assertThat(Redact.email("ab@x.com")).isEqualTo("a…@x.com");
        assertThat(Redact.email("ana@acme.example")).isEqualTo("a…a@acme.example");
    }

    @Test
    void nonAddressesAreNotMistakenForEmails() {
        assertThat(Redact.email("not-an-address")).isEqualTo("[redacted]");
        assertThat(Redact.email(null)).isNull();
    }

    @Test
    void longTextIsTruncatedSoOneStackTraceCannotFloodALogOrARow() {
        String huge = "x".repeat(10_000);

        assertThat(Redact.secrets(huge).length()).isLessThan(2_100);
    }

    @Test
    void nullAndEmptyInputPassThrough() {
        assertThat(Redact.secrets(null)).isNull();
        assertThat(Redact.secrets("")).isEmpty();
        assertThat(Redact.personalData(null)).isNull();
    }
}
