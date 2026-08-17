package com.example.profileservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the OpenAPI document served at {@code /v3/api-docs} and rendered by Swagger UI.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Supplies the API metadata shown at the top of the generated documentation.
     *
     * <p>Only the {@code info} block is set; paths and schemas are discovered from the controllers,
     * so an endpoint appears here without being listed in this class.
     */
    @Bean
    public OpenAPI profileServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Profile Service API")
                .description("Customer information: KYC status/webhook/admin override, contact info, alert & daily-summary preferences (FR3, FR4, FR9.1, FR10.1).")
                .version("v1"));
    }
}
