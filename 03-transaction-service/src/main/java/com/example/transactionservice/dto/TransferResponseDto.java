package com.example.transactionservice.dto;

import java.util.UUID;

/**
 * Confirms the outcome of a transfer to the client.
 *
 * @param transactionId the confirmation id and the stored row's primary key, unique per transfer and
 *     safe to quote back to the customer
 * @param status the name of a {@code TransactionStatus} constant, carried as text; a wire over the
 *     fraud threshold returns {@code PENDING_APPROVAL} here, meaning accepted but not yet settled
 * @param onUsTransfer {@code true} when funds landed on another account on this platform, whether
 *     an internal transfer or an external wire whose IBAN resolved on-platform; {@code false} when
 *     the money genuinely leaves the network
 */
public record TransferResponseDto(

        UUID transactionId,

        String status,

        boolean onUsTransfer

) {}