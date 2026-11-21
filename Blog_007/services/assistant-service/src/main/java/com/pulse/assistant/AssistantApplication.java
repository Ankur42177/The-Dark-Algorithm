package com.pulse.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * assistant-service — the "Ask Pulse" AI agent (Phase 8).
 * Roles (Spring profiles): "api" serves the chat endpoint; "embedder" consumes Kafka events and
 * fills the vector index. Locally you can run both in one process: --spring.profiles.active=api,embedder
 *
 * Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }
}
