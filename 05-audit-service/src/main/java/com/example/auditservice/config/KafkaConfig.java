package com.example.auditservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * Configures how Kafka records reach the listener.
 */
@Configuration
public class KafkaConfig {

    /**
     * Supplies the converter that turns the JSON payload of each record into a listener argument.
     *
     * <p>Registering it replaces Spring Kafka's default, under which
     * {@link com.example.auditservice.service.ProfileAuditListener} would receive the raw
     * {@code String} body and have to parse it itself. With this bean present the listener can
     * declare a {@code Map} parameter and take any shape of profile event without a shared DTO —
     * which is what lets producers add fields without a coordinated release of this service.
     *
     * @return a JSON converter with no type mapping configured, so the target type comes from the
     *     listener method signature and never from a type header on the record
     */
    @Bean
    public RecordMessageConverter recordMessageConverter() {
        return new StringJsonMessageConverter();
    }
}
