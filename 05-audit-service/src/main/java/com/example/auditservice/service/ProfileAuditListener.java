package com.example.auditservice.service;

import com.example.auditservice.model.AuditLogEntity;
import com.example.auditservice.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Turns every message on the {@code profile-events} Kafka topic into one immutable audit row.
 *
 * <p>This is the whole of the audit service's behaviour; it has no REST API and nothing else calls
 * into it.
 *
 * <p>It consumes the same topic that notification-service's {@code ProfileNotificationListener}
 * consumes, under its own {@code audit-service-group} consumer group. The separate group is what
 * gives each service a full copy of every message rather than the two of them sharing partitions;
 * changing the group ID to match would cause the two services to steal events from each other and
 * lose audit rows silently.
 */
@Service
public class ProfileAuditListener {

    private static final Logger logger = LoggerFactory.getLogger(ProfileAuditListener.class);
    
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public ProfileAuditListener(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records one profile event as an audit row.
     *
     * <p>The row is stamped with the current processing time, not the timestamp carried on the
     * event, so the stored time lags the actual change and shifts if the topic is ever replayed.
     *
     * <p>Every failure is caught and logged rather than rethrown. That means the offset is committed
     * and the message is not redelivered: a malformed event is dropped, not retried, and the audit
     * trail simply has no row for it. There is no dead-letter queue, so the log line is the only
     * remaining trace.
     *
     * @param eventPayload the deserialised JSON body; must carry a {@code userId} coercible to a
     *     {@code Long} and an {@code eventType}, or the event is dropped. A missing {@code changes}
     *     key is tolerated and stored as the literal JSON {@code null}
     */
    @KafkaListener(topics = "profile-events", groupId = "audit-service-group")
    public void consumeProfileUpdate(Map<String, Object> eventPayload) {
        try {
            logger.info("Received profile update event for Audit Logging: {}", eventPayload);

            AuditLogEntity auditRecord = toAuditRecord(eventPayload);

            auditLogRepository.save(auditRecord);
            logger.info("Successfully persisted audit log for User ID: {}", auditRecord.getUserId());

        } catch (Exception e) {
            logger.error("Failed to process profile audit event", e);
        }
    }

    private AuditLogEntity toAuditRecord(Map<String, Object> eventPayload) throws JsonProcessingException {
        Long userId = Long.valueOf(eventPayload.get("userId").toString());
        String eventType = (String) eventPayload.get("eventType");

        Object changesObj = eventPayload.get("changes");
        String changesJson = objectMapper.writeValueAsString(changesObj);

        return new AuditLogEntity(
                LocalDateTime.now(),
                userId,
                eventType,
                changesJson
        );
    }
}