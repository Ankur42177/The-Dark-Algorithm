package com.pulse.content.mail;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * "Send this email, but don't make the HTTP request wait for it" — for fire-and-forget emails of the PUBLIC forms
 * (newsletter confirmation, contact notification).
 *
 * <p>Why asynchronous?
 * <ul>
 *   <li><b>No timing side channel.</b> {@code POST /newsletter/subscribe} answers the same 202 for a new and an
 *       already-active address. If the new-address path waited ~300 ms for SMTP and the active path returned in
 *       5 ms, an attacker could still tell them apart with a stopwatch (email enumeration by timing).</li>
 *   <li><b>A slow mail server can't eat request threads.</b> SMTP has 3 s / 5 s timeouts, but a burst of form
 *       posts would still hold that many threads for seconds each.</li>
 * </ul>
 * Bounded on purpose: 2 sender threads and a queue of 500. If the queue is full (mail server down + a flood of
 * posts) the email is DROPPED and logged — never an unbounded queue that grows until the JVM runs out of memory.
 * Losing a confirmation email is harmless: the person just submits the form again.
 *
 * <p>Tests set {@code pulse.mail.async=false} so emails are "sent" before the HTTP response returns and the test
 * can read the link out of them immediately.
 */
@Component
public class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    private final Mailer mailer;
    private final ExecutorService executor;   // null = synchronous (tests)

    public MailDispatcher(Mailer mailer, @Value("${pulse.mail.async:true}") boolean async) {
        this.mailer = mailer;
        this.executor = async
                ? new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(500),
                        Thread.ofPlatform().name("mail-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy())
                : null;
    }

    /** Queue the email; failures are logged, never thrown (the caller has already answered the visitor). */
    public void dispatch(OutgoingEmail email, String kind) {
        Runnable send = () -> {
            try {
                mailer.send(email);
            } catch (RuntimeException e) {
                log.warn("Could not send {} email ({})", kind, e.getClass().getSimpleName());
            }
        };
        if (executor == null) {
            send.run();
            return;
        }
        try {
            executor.execute(send);
        } catch (RejectedExecutionException e) {
            log.warn("Mail queue full: {} email dropped", kind);
        }
    }

    /** On shutdown: give queued emails a few seconds to go out instead of silently losing them. */
    @PreDestroy
    void shutdown() throws InterruptedException {
        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
