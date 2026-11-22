package com.pulse.assistant.embed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * EMBEDDER ROLE housekeeping: reconcile the vector index with the site
 * <ul>
 *   <li>once at startup (in the background, so the app is "ready" immediately), and</li>
 *   <li>every {@code pulse.embedder.reconcile-interval} (default 30 min) — the safety net for changes that
 *       produce no event (a feed source removed, rows imported with SQL, an embedder outage).</li>
 * </ul>
 * Thanks to per-document hashes an "everything is up to date" run only costs some HTTP calls, no embeddings.
 *
 * <p>Spring notes: {@code @Profile("embedder")} → this bean exists only in the embedder role (the api pods never
 * reconcile on their own). {@code @EnableScheduling} switches on {@code @Scheduled} methods.
 * {@code @EventListener(ApplicationReadyEvent.class)} runs once the application has fully started.
 */
@Component
@Profile("embedder")
@EnableScheduling
public class EmbedderScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmbedderScheduler.class);

    private final EmbedderService embedder;
    private final boolean onStartup;

    public EmbedderScheduler(EmbedderService embedder, @Value("${pulse.embedder.reconcile-on-startup:true}") boolean onStartup) {
        this.embedder = embedder;
        this.onStartup = onStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileAtStartup() {
        if (onStartup) {
            Thread.ofVirtual().name("reconcile-startup").start(this::run);
        }
    }

    /** initialDelay: the startup run already covers the first minutes. */
    @Scheduled(initialDelayString = "${pulse.embedder.reconcile-interval:30m}", fixedDelayString = "${pulse.embedder.reconcile-interval:30m}")
    public void reconcilePeriodically() {
        run();
    }

    private void run() {
        try {
            embedder.reconcile(false);
        } catch (RuntimeException e) {
            log.warn("reconcile failed: {}", e.toString());
        }
    }
}
