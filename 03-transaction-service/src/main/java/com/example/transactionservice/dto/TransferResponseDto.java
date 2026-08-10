package com.example.transactionservice.dto;

import java.util.UUID;

public record TransferResponseDto(

        // A globally unique identifier generated specifically for this transfer event
        UUID transactionId,

        // The final state of the transaction (e.g., "COMPLETED")
        String status,

        // True when the funds moved to another real account on this platform (an internal
        // transfer, or an external wire whose IBAN resolved on-platform); false for a genuinely
        // external wire that leaves the network.
        boolean onUsTransfer

) {}