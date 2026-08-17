package com.example.transactionservice.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * States that an external wire exceeded the fraud threshold and is parked awaiting review.
 *
 * <p>Sent to {@code large-transfers-review} during wire initiation, at which point the sender has
 * already been debited: the funds are held rather than still spendable, and nothing releases them
 * in either direction until a reviewer resolves the transfer. A consumer that never answers leaves
 * the money stranded indefinitely.
 *
 * <p>Published directly to Kafka inside the initiating transaction, not after commit — so unlike
 * {@link FundsTransferredEvent} this event can exist for a wire whose local row was rolled back.
 *
 * @param transactionId id of the transaction sitting in {@code PENDING_APPROVAL}; also the Kafka
 *     record key, and the id a reviewer resolves the wire by
 * @param fromAccountId internal id of the already-debited source account, never {@code null}
 * @param amount the value that crossed the threshold; strictly greater than the threshold, since
 *     an amount exactly equal to it clears without review
 * @param iban destination IBAN as submitted, already mod-97 validated
 * @param swiftCode destination BIC as submitted; format-checked only, and only verified against the
 *     IBAN's real holder when the IBAN resolves to an account on this platform
 * @param beneficiaryName free text the sender typed, never verified against the destination bank
 */
public record LargeTransferRequestedEvent(

        UUID transactionId,

        Long fromAccountId,

        BigDecimal amount,

        String iban,

        String swiftCode,

        String beneficiaryName

) {}
