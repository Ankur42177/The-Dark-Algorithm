package com.pulse.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of content-service.
 *
 * <p>{@code @SpringBootApplication} is three annotations in one:
 * <ul>
 *   <li>{@code @Configuration}  – this class may declare beans.</li>
 *   <li>{@code @EnableAutoConfiguration} – Spring Boot looks at the jars on the classpath
 *       (Tomcat, Hibernate, Flyway, PostgreSQL driver ...) and configures them for us.</li>
 *   <li>{@code @ComponentScan} – find every {@code @Component/@Service/@Repository/@RestController}
 *       in this package ({@code com.pulse.content}) and its sub-packages, and create them.</li>
 * </ul>
 * Explained line by line in Learning_project/phase-01-content-service/05-code-walkthrough.md
 */
@SpringBootApplication
public class ContentServiceApplication {

    public static void main(String[] args) {
        // Starts the Spring "container": creates all beans, runs Flyway, starts Tomcat on port 8081.
        SpringApplication.run(ContentServiceApplication.class, args);
    }
}
