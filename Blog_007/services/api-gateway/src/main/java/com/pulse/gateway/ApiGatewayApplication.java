package com.pulse.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Pulse api-gateway: the single front door for API traffic (PLAN §7.1).
 * Runs on Spring Cloud Gateway (WebFlux + Netty): a small number of event-loop threads proxy thousands
 * of concurrent requests without blocking.
 *
 * <p>Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC
 */
@SpringBootApplication
@ConfigurationPropertiesScan   // finds @ConfigurationProperties records such as GatewayProps
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
