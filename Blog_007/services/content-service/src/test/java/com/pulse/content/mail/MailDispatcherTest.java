package com.pulse.content.mail;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public forms must answer as fast for a NEW address (an email goes out) as for an ACTIVE one (nothing to
 * send); otherwise response time leaks who is subscribed. Unit test with a deliberately slow mail server.
 */
class MailDispatcherTest {

    private static final OutgoingEmail MAIL = new OutgoingEmail("a@b.co", null, "s", "t", "<p>t</p>", Map.of());

    @Test
    void dispatchReturnsImmediatelyEvenWhenSmtpIsSlow() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        Mailer slowSmtp = email -> {
            sleep(300);
            delivered.countDown();
        };
        MailDispatcher dispatcher = new MailDispatcher(slowSmtp, true);

        long start = System.nanoTime();
        dispatcher.dispatch(MAIL, "confirm");
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(tookMs).isLessThan(50);                          // the caller never waits for SMTP
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();  // ... but the email still goes out
        dispatcher.shutdown();
    }

    @Test
    void aFailingMailServerNeverThrowsIntoTheCaller() throws Exception {
        MailDispatcher dispatcher = new MailDispatcher(email -> { throw new IllegalStateException("SMTP down"); }, false);
        dispatcher.dispatch(MAIL, "confirm");                       // logged, not thrown
        dispatcher.shutdown();
    }

    @Test
    void aFullQueueDropsInsteadOfGrowingForever() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Mailer stuck = email -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        MailDispatcher dispatcher = new MailDispatcher(stuck, true);
        for (int i = 0; i < 2 + 500 + 50; i++) {                    // 2 busy threads + 500 queued + 50 extra
            dispatcher.dispatch(MAIL, "confirm");                   // must not block or throw
        }
        release.countDown();
        dispatcher.shutdown();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
