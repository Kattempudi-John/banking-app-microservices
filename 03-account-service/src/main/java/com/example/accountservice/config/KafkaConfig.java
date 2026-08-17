package com.example.accountservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * Configures how inbound Kafka records are converted before reaching listener methods.
 */
@Configuration
public class KafkaConfig {

    /**
     * Supplies the converter that deserialises JSON record payloads into listener parameter types.
     *
     * <p>Records arrive as strings on the wire; this converter is what lets a {@code @KafkaListener}
     * declare a typed event object as its parameter instead of parsing JSON by hand. Removing it does
     * not fail at startup — listeners simply start rejecting every record at runtime.
     *
     * @return a JSON converter applied to every listener container in this context
     */
    @Bean
    public RecordMessageConverter recordMessageConverter() {
        return new StringJsonMessageConverter();
    }
}
