package com.example.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * Configures how raw Kafka records are turned into the arguments this service's listeners declare.
 */
@Configuration
public class KafkaConfig {

    /**
     * Converts the JSON string payload of each record into the parameter type its listener declares.
     *
     * <p>This is what lets {@code TransactionAlertListener} accept a {@code FundsTransferredEvent}
     * and the other listeners accept a {@code Map<String, Object>} rather than every listener
     * parsing a {@code String} itself. The target type comes from the listener method signature, so
     * producers and consumers stay decoupled: an unknown field in the payload is ignored rather
     * than fatal, provided the target record is annotated to allow it.
     *
     * @return a JSON converter applied to every {@code @KafkaListener} in this service
     */
    @Bean
    public RecordMessageConverter recordMessageConverter() {
        return new StringJsonMessageConverter();
    }
}
