package com.pulse.assistant.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig implements WebMvcConfigurer {

    private final AssistantProperties props;

    public AppConfig(AssistantProperties props) {
        this.props = props;
    }

    /** HTTP client for content-service, with timeouts on EVERY call (a slow dependency must not hang a chat). */
    @Bean
    RestClient contentRestClient() {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl(props.contentServiceUrl()).requestFactory(factory).build();
    }

    /**
     * CIRCUIT BREAKER around the LLM. If ≥ 50 % of the last 10 calls failed (or took > 15 s), the breaker
     * OPENS: for 30 s we don't even try the model and answer in "search mode" instead. Then it lets a few
     * trial calls through (HALF_OPEN) and closes again if they succeed.
     */
    @Bean
    CircuitBreaker llmCircuitBreaker() {
        return CircuitBreaker.of("llm", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(15))
                .slowCallRateThreshold(80)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
    }

    /** Each chat runs on its own virtual thread: thousands of long-lived streams cost almost nothing. */
    @Bean(destroyMethod = "close")
    ExecutorService chatExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** The website calls /api/v1/assistant/chat directly until the gateway (Phase 5) fronts it. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (props.corsOrigins() == null || props.corsOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/v1/assistant/**")
                .allowedOrigins(props.corsOrigins().toArray(String[]::new))
                .allowedMethods("POST")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }

    @Bean
    OpenAPI assistantOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Pulse assistant-service API")
                .version("v1")
                .description("The Ask Pulse AI agent: streaming chat with tools, RAG search and graceful fallback. "
                        + "Designed and developed by Ankur Kumar. © 2026 Ravenclaw_007 LLC.")
                .contact(new Contact().name("Ankur Kumar · Ravenclaw_007 LLC").url("https://github.com/Ankur42177/The-Dark-Algorithm")));
    }
}
