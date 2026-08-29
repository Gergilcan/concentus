package com.concentus.mail;

import com.concentus.integration.Redact;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Properties;

/**
 * Sends mail over SMTP with Jakarta Mail — the same library the IMAP trigger reads with, so the
 * product's two mail paths share one dependency and one set of provider quirks.
 *
 * <p>One connection per message, opened and closed here. A hand-off sends at most a handful of
 * mails at the end of a run, and a pooled SMTP connection would be a thing to keep alive, time
 * out and reconnect for no measurable gain.
 */
@Component
public class SmtpMailSender implements MailSender {

    private final int connectTimeoutMs;

    public SmtpMailSender(@Value("${mail.connect-timeout-ms:20000}") int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public void send(MailAccount account, String to, String subject, String body) {
        InternetAddress[] recipients;
        try {
            recipients = InternetAddress.parse(to, true);
        } catch (AddressException e) {
            throw new MailSendException("'" + to + "' is not a valid list of addresses: " + e.getMessage(), e);
        }
        if (recipients.length == 0) {
            throw new MailSendException("No recipient.");
        }

        String protocol = account.starttls() ? "smtp" : "smtps";
        Session session = Session.getInstance(properties(account, protocol));
        Transport transport = null;
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(account.from()));
            message.setRecipients(Message.RecipientType.TO, recipients);
            message.setSubject(subject == null ? "" : subject, "UTF-8");
            // Plain text on purpose: the body is a run's output or a verification report, written
            // by an agent as text. Wrapping it in HTML would only give a mail client something to
            // mangle.
            message.setText(body == null ? "" : body, "UTF-8");
            message.setSentDate(new Date());
            message.saveChanges();

            transport = session.getTransport(protocol);
            transport.connect(account.host(), account.port(), account.username(), account.password());
            transport.sendMessage(message, message.getAllRecipients());
        } catch (MessagingException e) {
            // Redacted before it can reach a run log: a provider's refusal sometimes quotes the
            // credentials it was given.
            throw new MailSendException("SMTP submission to " + account.host() + " failed: "
                    + Redact.secrets(String.valueOf(e.getMessage())), e);
        } finally {
            close(transport);
        }
    }

    private Properties properties(MailAccount account, String protocol) {
        Properties props = new Properties();
        props.put("mail.transport.protocol", protocol);
        props.put("mail." + protocol + ".host", account.host());
        props.put("mail." + protocol + ".port", String.valueOf(account.port()));
        props.put("mail." + protocol + ".auth", "true");
        props.put("mail." + protocol + ".connectiontimeout", String.valueOf(connectTimeoutMs));
        props.put("mail." + protocol + ".timeout", String.valueOf(connectTimeoutMs));
        props.put("mail." + protocol + ".writetimeout", String.valueOf(connectTimeoutMs));
        if (account.starttls()) {
            // Required, not merely enabled: a server that declines the upgrade must not receive
            // the password in the clear as a fallback.
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        } else {
            props.put("mail.smtps.ssl.enable", "true");
        }
        return props;
    }

    private static void close(Transport transport) {
        try {
            if (transport != null && transport.isConnected()) transport.close();
        } catch (MessagingException ignored) {
            // The message is already on its way or already refused; a failure to hang up
            // politely is not the error worth reporting.
        }
    }
}
