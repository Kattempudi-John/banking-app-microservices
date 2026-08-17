package com.example.authservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Names and versions the generated OpenAPI document for this service.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Supplies the title, description, and version shown on the Swagger UI page.
     *
     * <p>Only the document metadata is set here; the operations themselves are still discovered
     * from the controllers, so declaring this bean does not restrict what gets published.
     *
     * @return the OpenAPI document root, never {@code null}
     */
    @Bean
    public OpenAPI authServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Auth Service API")
                .description("Identity & Access Management: login, device fingerprinting, TOTP/SMS 2FA, session refresh/logout (FR1, FR2).")
                .version("v1"));
    }
}
