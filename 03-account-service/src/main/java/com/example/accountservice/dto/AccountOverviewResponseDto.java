package com.example.accountservice.dto;

import java.math.BigDecimal;

public record AccountOverviewResponseDto(
        Long accountId,
        String accountType,
        BigDecimal availableBalance,
        String routingNumber, // Routing numbers are public banking info and sent in plain text
        String maskedAccountNumber,
        // The unmasked number, for the owner's own eyes only. This DTO is built exclusively for
        // GET /api/v1/accounts, which resolves the account set from the caller's JWT - so a user can
        // only ever see their own here. It's needed because the Transfer page pays another user BY
        // account number, which means the recipient has to be able to read their own and pass it on;
        // maskedAccountNumber alone made that impossible from the UI.
        //
        // Deliberately NOT added to InternalAccountController's recipient-lookup response, which
        // describes somebody ELSE's account and must stay masked.
        String accountNumber,
        String iban, // Meant to be shared to receive transfers, so unmasked like routingNumber
        String swiftCode, // Identifies this bank, not the individual account - same value for everyone
        String status
) {
}