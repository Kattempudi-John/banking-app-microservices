package com.example.transactionservice.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * States that funds have moved between two ledger accounts and the movement has committed.
 *
 * <p>Published in-process by {@code TransferService} and forwarded to the
 * {@code successful-transfers} Kafka topic by {@code TransferEventPublisher} only after that
 * transaction commits, so its existence means the money really moved.
 *
 * <p>Emitted from the two ledger transfer paths only — own-account transfers and payments to a
 * recipient's account number. External wires never produce one, on-us or not, so a consumer that
 * reads this topic as "every completed transfer" silently misses all wire activity.
 *
 * @param userId the sender who initiated the transfer; owns {@code fromAccountId}, and owns
 *     {@code toAccountId} too only on the own-accounts path
 * @param fromAccountId internal id of the debited account, never {@code null}
 * @param toAccountId internal id of the credited account, never {@code null}; belongs to a
 *     different user when the transfer was addressed to an account number
 * @param amount the exact value moved, positive; carried as {@code BigDecimal} so consumers must
 *     not compare it with {@code equals}, which distinguishes {@code 5000.0} from {@code 5000.00}
 * @param transactionId confirmation id generated locally per transfer and also used as the Kafka
 *     record key, so every event for one transfer lands on the same partition
 */
public record FundsTransferredEvent(

        Long userId,

        Long fromAccountId,

        Long toAccountId,

        BigDecimal amount,

        UUID transactionId

) {}
