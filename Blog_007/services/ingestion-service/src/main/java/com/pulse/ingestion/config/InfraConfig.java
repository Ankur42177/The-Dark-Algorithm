package com.pulse.ingestion.config;

import com.pulse.events.EventTypes;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;

import javax.sql.DataSource;

@Configuration
public class InfraConfig {

    /** ShedLock stores locks in Postgres (table "shedlock"); usingDbTime() avoids clock skew between pods. */
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    /** Same definition as in content-service: whoever starts first creates it, the other is a no-op. */
    @Bean
    NewTopic articleEventsTopic(@Value("${pulse.kafka.partitions:3}") int partitions, @Value("${pulse.kafka.replicas:1}") short replicas) {
        return TopicBuilder.name(EventTypes.TOPIC_ARTICLE_EVENTS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    OpenAPI ingestionOpenApi() {
        return new OpenAPI().info(new Info().title("Pulse ingestion-service API").version("v1")
                .description("Feed sources, ingestion runs and the dead-letter queue. Designed and developed by Ankur Kumar. © 2026 Ravenclaw_007 LLC.")
                .contact(new Contact().name("Ankur Kumar · Ravenclaw_007 LLC").url("https://github.com/Ankur42177/The-Dark-Algorithm")));
    }
}
