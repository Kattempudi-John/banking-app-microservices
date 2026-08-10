package com.example.accountservice.dto;

import java.math.BigDecimal;

public record AccountOverviewResponseDto(
        Long accountId,
        String accountType,
        BigDecimal availableBalance,
        String routingNumber, // Routing numbers are public banking info and sent in plain text
        String maskedAccountNumber,
        String iban, // Meant to be shared to receive transfers, so unmasked like routingNumber
        String swiftCode, // Identifies this bank, not the individual account - same value for everyone
        String status
) {
}