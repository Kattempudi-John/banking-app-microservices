package com.example.transactionservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the OpenAPI document served at {@code /v3/api-docs} and rendered by Swagger UI.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Describes the transaction service API.
     *
     * @return the document backing {@code /swagger-ui.html}, which {@code SecurityConfig} leaves
     *     publicly reachable because it exposes documentation rather than application data
     */
    @Bean
    public OpenAPI transactionServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Transaction Service API")
                .description("Payments & transfers: atomic internal transfers and external wires with fraud-threshold review, delegating account balance mutations to account-service (FR7, FR8).")
                .version("v1"));
    }
}
