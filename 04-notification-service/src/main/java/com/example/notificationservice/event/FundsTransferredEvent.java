package com.example.notificationservice.event;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code successful-transfers} payload this service consumes to decide whether to raise a
 * transaction alert.
 *
 * <p>A deliberate copy of transaction-service's record of the same name rather than a shared type:
 * two classes in two services deserializing the same JSON, kept loosely coupled by ignoring unknown
 * properties so the producer can add fields without breaking this consumer.
 *
 * @param userId the user who initiated the transfer and the only id valid for preference and
 *     threshold lookups — {@code fromAccountId} is drawn from a different id sequence entirely and
 *     will silently match the wrong user's preferences
 * @param fromAccountId the debited account; an account identifier, not an account number, so only
 *     its last four digits are meaningful to show
 * @param toAccountId the credited account, which may belong to the same user as {@code userId}
 * @param amount the transfer value compared against the user's {@code alertThresholdAmount}; an
 *     alert is raised when it is greater than or equal to the threshold, not strictly greater
 * @param transactionId the reference printed in the alert so a user can identify the payment
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FundsTransferredEvent(
        Long userId,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        UUID transactionId
) {}