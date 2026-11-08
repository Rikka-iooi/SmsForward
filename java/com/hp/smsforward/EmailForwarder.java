package com.hp.smsforward;

/**
 * Email channel entry point.
 */
final class EmailForwarder {
    private EmailForwarder() {
    }

    static void send(Config config, String subject, String body) throws Exception {
        new SmtpClient(config).send(subject, body);
    }
}
