package com.concentus.mail;

/**
 * Sends one plain-text mail.
 *
 * <p>An interface rather than the SMTP class itself so the hand-off that decides WHETHER to send
 * can be tested without a mail server, and without a mock that knows JavaMail's shape: a fake
 * that records what it was handed is the whole test double.
 */
public interface MailSender {

    /**
     * @param to comma-separated recipients, as typed on the node
     * @throws MailSendException when the server refused the submission or could not be reached;
     *                           the message is safe to put in a run log
     */
    void send(MailAccount account, String to, String subject, String body);

    /** A submission that failed. Always carries a message an operator can act on, never a secret. */
    class MailSendException extends RuntimeException {
        public MailSendException(String message, Throwable cause) {
            super(message, cause);
        }

        public MailSendException(String message) {
            super(message);
        }
    }
}
