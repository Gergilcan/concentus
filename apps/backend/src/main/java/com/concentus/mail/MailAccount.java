package com.concentus.mail;

/**
 * Everything an SMTP submission needs, resolved: the server, the login, and the password itself.
 *
 * <p>Built at the moment of sending and dropped straight after — it is the one shape in the
 * product that carries a mail password in the clear, which is why it lives nowhere. The node
 * holds a credential id; the run log holds addresses; this holds the secret for the length of one
 * {@code Transport.connect}.
 *
 * @param starttls true for plain SMTP upgraded with STARTTLS (port 587, the common submission
 *                 setup); false for SMTP over implicit TLS (port 465). Never plain: a password
 *                 does not travel unencrypted whichever box was ticked, the same stance the IMAP
 *                 reader takes on port 143.
 * @param from     the address the mail is sent as — most providers refuse a From that is not the
 *                 authenticated mailbox, so it doubles as the default login
 */
public record MailAccount(String host, int port, boolean starttls, String username, String from,
                          String password) {

    /** A record's generated toString would print the password; this one never does. */
    @Override
    public String toString() {
        return "MailAccount[" + username + "@" + host + ":" + port + (starttls ? " starttls" : " tls") + "]";
    }
}
