package com.pulse.content.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title, description and ownership shown at the top of Swagger UI (/swagger-ui.html). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI pulseOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Pulse content-service API")
                .version("v1")
                .description("Posts, sections, trending and admin APIs of the Pulse platform. "
                        + "Designed and developed by Ankur Kumar. © 2026 Ravenclaw_007 LLC.")
                .contact(new Contact().name("Ankur Kumar · Ravenclaw_007 LLC").url("https://github.com/Ankur42177/The-Dark-Algorithm")));
    }
}
