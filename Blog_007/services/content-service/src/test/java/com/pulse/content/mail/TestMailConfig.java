package com.pulse.content.mail;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the real {@link Mailer} in every integration test. {@code @Primary} = "when several beans of this type
 * exist, inject THIS one". Imported by IntegrationTestBase so all test classes share one Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfig {

    @Bean
    @Primary
    CapturingMailer capturingMailer() {
        return new CapturingMailer();
    }
}
