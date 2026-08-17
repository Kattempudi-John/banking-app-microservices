package com.example.profileservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * Configures how inbound Kafka records are converted before reaching a listener.
 *
 * <p>Records arrive as JSON strings, so {@code @KafkaListener} methods in this service can declare a
 * typed payload parameter directly.
 */
@Configuration
public class KafkaConfig {

    /**
     * Supplies the JSON converter applied to every listener in this service.
     *
     * <p>Declared as a bare {@code @Bean} so Spring Boot wires it into the shared listener container
     * factory, covering all listeners at once rather than each declaring its own converter. The
     * target type is taken from the listener method's payload parameter, so producers that send no
     * type headers are still consumable.
     */
    @Bean
    public RecordMessageConverter recordMessageConverter() {
        return new StringJsonMessageConverter();
    }
}
