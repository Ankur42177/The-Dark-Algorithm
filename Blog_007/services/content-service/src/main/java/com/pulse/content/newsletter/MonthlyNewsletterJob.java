package com.pulse.content.newsletter;

import com.pulse.content.newsletter.NewsletterDtos.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Runs the monthly newsletter on a timer.
 *
 * <p>Cron "0 0 9 1 * *" = second 0, minute 0, hour 9, day-of-month 1, every month, any weekday → 09:00 on the 1st.
 * {@code zone = "UTC"}: without it the schedule follows the server's local clock, which differs between a laptop,
 * a container and a cloud node. Tests disable it with {@code pulse.newsletter.cron=-} ("-" = never).
 *
 * <p>With 3 replicas, all 3 fire at 09:00. No lock is needed: {@link NewsletterService#sendMonthly} claims each
 * subscriber with a UNIQUE row, so each person still gets exactly one email (the replicas simply share the work).
 */
@Component
public class MonthlyNewsletterJob {

    private final NewsletterService newsletter;

    public MonthlyNewsletterJob(NewsletterService newsletter) {
        this.newsletter = newsletter;
    }

    @Scheduled(cron = "${pulse.newsletter.cron:0 0 9 1 * *}", zone = "UTC")
    public SendResult run() {
        return newsletter.sendMonthly(YearMonth.now(ZoneOffset.UTC));
    }
}
