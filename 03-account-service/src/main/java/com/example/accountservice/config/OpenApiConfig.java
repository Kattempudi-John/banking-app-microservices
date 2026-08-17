package com.example.accountservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the OpenAPI document served at {@code /v3/api-docs} and rendered by the Swagger UI.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Describes this service's API surface for the generated documentation.
     *
     * @return metadata only; springdoc discovers the operations themselves from the controllers
     */
    @Bean
    public OpenAPI accountServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Account Service API")
                .description("Sole owner of the accounts/transactions ledger: masked account dashboard, paginated transaction history (FR5, FR6). Also exposes an internal-only API for transfer/debit/credit calls from transaction-service and batch-balance lookups from notification-service.")
                .version("v1"));
    }
}
