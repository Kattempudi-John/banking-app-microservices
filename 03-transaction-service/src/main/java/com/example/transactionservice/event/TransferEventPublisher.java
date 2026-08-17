package com.example.transactionservice.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Forwards committed ledger transfers from the in-process event bus to Kafka.
 *
 * <p>The bridge exists so that {@code TransferService} can announce a transfer without knowing
 * Kafka is involved, and so that the announcement is tied to the database commit rather than to the
 * moment the service decided to publish.
 */
@Component
public class TransferEventPublisher {

    private final KafkaTemplate<String, FundsTransferredEvent> kafkaTemplate;
    private static final String TOPIC = "successful-transfers";

    public TransferEventPublisher(KafkaTemplate<String, FundsTransferredEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends a committed transfer to {@code successful-transfers}, keyed by transaction id.
     *
     * <p>Bound to the commit rather than to the publish call, so a transfer whose transaction rolls
     * back is never announced. The cost of that choice is the mirror case: the send happens after
     * the commit and outside the transaction, so a Kafka failure here cannot undo money that has
     * already moved — the transfer stands and only the downstream notification is lost.
     *
     * <p>The only source of this event is the pair of ledger transfer paths in
     * {@code TransferService}. No wire, on-us or external, publishes to this topic, so downstream
     * consumers treating it as the complete record of money leaving an account will be wrong for
     * every wire.
     *
     * @param event never {@code null}; its {@code transactionId} is used as the record key so all
     *     events for one transfer are ordered on a single partition
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleFundsTransferredEvent(FundsTransferredEvent event) {
        kafkaTemplate.send(TOPIC, event.transactionId().toString(), event);
    }
}
