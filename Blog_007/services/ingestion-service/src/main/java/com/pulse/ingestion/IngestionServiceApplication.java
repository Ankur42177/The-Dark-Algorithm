package com.pulse.ingestion;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ingestion-service — ONE codebase, TWO roles chosen with Spring profiles (PLAN §7.3):
 * <ul>
 *   <li>{@code scheduler}: finds due feeds every minute, enqueues jobs, serves the admin API (1–2 replicas, ShedLock)</li>
 *   <li>{@code worker}: consumes jobs and fetches feeds (0..N replicas, autoscaled on queue length by KEDA)</li>
 * </ul>
 * Locally both roles run in one process (spring.profiles.default = scheduler,worker).
 * Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC
 */
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT50S")
public class IngestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}
