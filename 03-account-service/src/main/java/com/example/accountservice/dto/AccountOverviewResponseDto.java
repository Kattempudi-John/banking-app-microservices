package com.example.accountservice.dto;

import java.math.BigDecimal;

/**
 * Owner-facing view of a single account, as returned by {@code GET /api/v1/accounts}.
 *
 * <p>This shape is for the account owner alone. That endpoint resolves the account set from the
 * caller's JWT, so a user can only ever receive their own accounts in it — which is the only reason
 * it is safe to carry {@code accountNumber} unmasked. It must not be reused for any response
 * describing somebody else's account; the internal recipient-lookup response deliberately omits the
 * unmasked number for exactly that reason.
 *
 * @param accountId the ledger identifier, never {@code null}
 * @param accountType the {@code AccountType} constant rendered as its name
 * @param availableBalance scale 4, may be zero, never {@code null}
 * @param routingNumber 9 digits, unmasked because routing numbers are public banking information
 * @param maskedAccountNumber all but the trailing digits replaced; safe to display anywhere
 * @param accountNumber the full unmasked number, owner's eyes only — the Transfer page pays another
 *     user by account number, so a recipient must be able to read their own and pass it on
 * @param iban may be {@code null} on accounts predating the IBAN backfill; unmasked because it is
 *     meant to be shared in order to receive transfers
 * @param swiftCode identifies this bank rather than the account, so the same value for every user
 * @param status the {@code AccountStatus} constant rendered as its name
 */
public record AccountOverviewResponseDto(
        Long accountId,
        String accountType,
        BigDecimal availableBalance,
        String routingNumber,
        String maskedAccountNumber,
        String accountNumber,
        String iban,
        String swiftCode,
        String status
) {
}