package com.pulse.content.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test double for {@link Mailer}: keeps every "sent" email in memory so tests can read links out of them.
 * Thread-safe (the monthly-job test sends from two threads at once). {@link #failing} simulates an SMTP outage.
 */
public class CapturingMailer implements Mailer {

    private final List<OutgoingEmail> sent = new CopyOnWriteArrayList<>();
    public final AtomicBoolean failing = new AtomicBoolean(false);

    @Override
    public void send(OutgoingEmail email) {
        if (failing.get()) {
            throw new org.springframework.mail.MailSendException("simulated SMTP failure");
        }
        sent.add(email);
    }

    public List<OutgoingEmail> sentTo(String address) {
        return sent.stream().filter(e -> e.to().equals(address)).toList();
    }

    public List<OutgoingEmail> all() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
        failing.set(false);
    }
}
