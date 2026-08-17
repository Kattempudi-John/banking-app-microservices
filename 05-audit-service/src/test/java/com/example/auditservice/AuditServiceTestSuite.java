package com.example.auditservice;

import com.example.auditservice.model.AuditLogEntity;
import com.example.auditservice.repository.AuditLogRepository;
import com.example.auditservice.service.ProfileAuditListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Covers the whole of audit-service's behaviour: {@link ProfileAuditListener} turning a
 * {@code profile-events} message into one immutable {@link AuditLogEntity}, plus a structural guard
 * on the entity itself.
 *
 * <h2>What is real and what is mocked</h2>
 *
 * <p>The listener is the real Spring bean and so is the {@link ObjectMapper} injected here — it is
 * the same instance the listener serializes with, which means the round-trip in
 * {@code consumeProfileUpdate_changesMapWithOldAndNewValues_serializesBothIntoChangedFieldsJson}
 * shares Jackson's configuration with the code it is checking and would not catch a mapper
 * misconfiguration that is symmetric on the way in and out. Only {@link AuditLogRepository} is
 * replaced, by {@code @MockBean}, so every assertion is made against the entity handed to
 * {@code save(...)} rather than against a stored row.
 *
 * <h2>The listener is called directly, not through Kafka</h2>
 *
 * <p>Every test here invokes {@code consumeProfileUpdate(Map)} as a plain Java method. No broker,
 * no embedded Kafka, no {@code @EmbeddedKafka}. That proves the payload-to-entity mapping and the
 * listener's error handling; it proves <em>nothing</em> about the Kafka wiring — the topic name,
 * the {@code audit-service-group} consumer group, and the deserializer that produces the
 * {@code Map} in the first place are all untested. A change that broke the {@code @KafkaListener}
 * annotation would leave this suite green.
 *
 * <h2>Test configuration</h2>
 *
 * <p>{@code @SpringBootTest} with no web layer and no slice annotation: the unit under test is a
 * message listener, not a controller or a repository, so neither {@code @WebMvcTest} nor
 * {@code @DataJpaTest} fits, and the point of booting the full context is to get the real listener
 * with the real application {@code ObjectMapper} wired into it.
 *
 * <p>Unlike notification-service, this module does have {@code src/test/resources/application.properties}:
 * H2 in PostgreSQL mode with {@code ddl-auto=none} and Flyway disabled. That combination leaves the
 * database completely empty — {@code profile_audit_logs} is never created — and it is viable only
 * because {@link AuditLogRepository} is mocked, so no statement is ever issued against the table.
 * Un-mocking the repository in a future test would fail on a missing relation, not on a
 * connection.
 *
 * <h2>Fixture lifecycle</h2>
 *
 * <p>There is no {@code @BeforeEach} and no shared mutable state; each test builds its own payload
 * inline. Spring resets the {@code @MockBean} between test methods, which is what lets
 * {@code verify(..., times(1))} below mean "once in this test" rather than "once in this class".
 */
@SpringBootTest
class AuditServiceTestSuite {

    @Autowired
    private ProfileAuditListener profileAuditListener;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("Block 1: Valid ProfileUpdatedEvent payload is mapped and persisted correctly - [MEANT TO PASS]")
    void consumeProfileUpdate_validProfileUpdatedEventPayload_persistsAuditRowWithMappedFields() {
        // userId arrives as the String "100", not a number: the Kafka JSON deserializer hands the
        // listener a raw Map, and the listener has to coerce it to the entity's Long 100L.
        Map<String, Object> payload = Map.of(
                "userId", "100",
                "eventType", "PHONE_CHANGE",
                "changes", Map.of("phoneNumber", Map.of("old", "+15551111111", "new", "+15552222222"))
        );

        profileAuditListener.consumeProfileUpdate(payload);

        verify(auditLogRepository).save(argThat(entity -> entity.getUserId().equals(100L)));
        verify(auditLogRepository).save(argThat(entity -> "PHONE_CHANGE".equals(entity.getEventType())));
        verify(auditLogRepository).save(argThat(entity -> entity.getTimestamp() != null));
        verify(auditLogRepository).save(argThat(entity -> entity.getChangedFieldsJson().contains("+15552222222")));
    }

    @Test
    @DisplayName("Block 2: The changes object is faithfully serialized into changed_fields_json - [MEANT TO PASS]")
    void consumeProfileUpdate_changesMapWithOldAndNewValues_serializesBothIntoChangedFieldsJson() {
        Map<String, Object> payload = Map.of(
                "userId", "200",
                "eventType", "ADDRESS_CHANGE",
                "changes", Map.of("addressLine1", Map.of("old", "123 Main St", "new", "456 Oak Ave"))
        );

        profileAuditListener.consumeProfileUpdate(payload);

        // Parsing the stored string back into a Map, rather than substring-matching it as Block 1
        // does, is what proves the nested old/new structure survived - a flattened or
        // double-escaped JSON string would still contain the raw values and pass a contains() check.
        verify(auditLogRepository).save(argThat(entity -> {
            try {
                Map<?, ?> parsedChanges = objectMapper.readValue(entity.getChangedFieldsJson(), Map.class);
                Map<?, ?> addressChange = (Map<?, ?>) parsedChanges.get("addressLine1");
                return "123 Main St".equals(addressChange.get("old"));
            } catch (Exception e) {
                return false;
            }
        }));
        verify(auditLogRepository).save(argThat(entity -> {
            try {
                Map<?, ?> parsedChanges = objectMapper.readValue(entity.getChangedFieldsJson(), Map.class);
                Map<?, ?> addressChange = (Map<?, ?>) parsedChanges.get("addressLine1");
                return "456 Oak Ave".equals(addressChange.get("new"));
            } catch (Exception e) {
                return false;
            }
        }));
    }

    /**
     * Pins the listener's swallow-and-log failure policy, which is a deliberate trade-off rather
     * than an oversight: rethrowing would leave the offset uncommitted and the broker would redeliver
     * the same broken message forever. The cost is that the event is dropped with no dead-letter
     * queue behind it, so the absence of a {@code save} here is the audit trail permanently missing
     * a row, not a retry pending.
     */
    @Test
    @DisplayName("Block 3: Malformed payload (missing userId) is swallowed, not persisted, does not crash the consumer - [MEANT TO PASS]")
    void consumeProfileUpdate_payloadMissingUserId_swallowsExceptionAndPersistsNothing() {
        Map<String, Object> payload = Map.of("eventType", "PHONE_CHANGE");

        assertThatCode(() -> profileAuditListener.consumeProfileUpdate(payload)).doesNotThrowAnyException();

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Block 4: Two events for different users each produce exactly one record - [MEANT TO PASS]")
    void consumeProfileUpdate_eventsForTwoDifferentUsers_persistsExactlyOneRecordPerUser() {
        Map<String, Object> firstEvent = Map.of(
                "userId", "300", "eventType", "PHONE_CHANGE",
                "changes", Map.of("phoneNumber", Map.of("old", "111", "new", "222")));
        Map<String, Object> secondEvent = Map.of(
                "userId", "400", "eventType", "ADDRESS_CHANGE",
                "changes", Map.of("addressLine1", Map.of("old", "A St", "new", "B St")));

        profileAuditListener.consumeProfileUpdate(firstEvent);
        profileAuditListener.consumeProfileUpdate(secondEvent);

        // Each matcher is checked independently, so what these four calls actually prove is that
        // exactly one saved row carried user 300, exactly one carried user 400, exactly one carried
        // PHONE_CHANGE and exactly one carried ADDRESS_CHANGE - i.e. no duplicated or merged writes.
        // They do not pin the user to its own event type, so a listener that swapped the two event
        // types between the two rows would still satisfy them.
        verify(auditLogRepository, times(1)).save(argThat(e -> e.getUserId().equals(300L)));
        verify(auditLogRepository, times(1)).save(argThat(e -> "PHONE_CHANGE".equals(e.getEventType())));
        verify(auditLogRepository, times(1)).save(argThat(e -> e.getUserId().equals(400L)));
        verify(auditLogRepository, times(1)).save(argThat(e -> "ADDRESS_CHANGE".equals(e.getEventType())));
    }

    /**
     * The only structural test in the suite: it inspects {@link AuditLogEntity}'s declared methods
     * by reflection instead of exercising any behaviour.
     *
     * <p>Append-only-ness is enforced in three independent places (no setters, {@code updatable = false}
     * on every column, no update or delete SQL anywhere in the service). Nothing fails loudly if one
     * of those layers is removed, so this test guards the one that is easiest to reintroduce by
     * accident — someone adding a setter to satisfy a mapper or an unrelated bug fix. Matching on
     * the {@code set} name prefix is a heuristic: it would not catch a mutator named
     * {@code updateEventType} or a non-final public field.
     */
    @Test
    @DisplayName("Final Block: AuditLogEntity exposes no setters - the append-only contract is enforced at the Java layer, not just by convention - [MEANT TO PASS]")
    void auditLogEntity_declaredMethods_containNoSettersEnforcingAppendOnlyContract() {
        Method[] methods = AuditLogEntity.class.getDeclaredMethods();

        boolean hasSetter = Arrays.stream(methods).anyMatch(m -> m.getName().startsWith("set"));

        assertThat(hasSetter)
                .as("AuditLogEntity must remain immutable (getters only) to satisfy FR4.3 AC4")
                .isFalse();
    }
}
