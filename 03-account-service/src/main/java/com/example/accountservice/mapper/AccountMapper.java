package com.example.accountservice.mapper;

import org.springframework.stereotype.Component;

import com.example.accountservice.dto.AccountOverviewResponseDto;
import com.example.accountservice.model.AccountEntity;

/**
 * Converts account entities into the response shape the API hands back, masking the account number.
 *
 * <p>Kept as its own component rather than folded into the service or the entity so that the
 * entity-to-database mapping and the entity-to-API mapping can change independently.
 */
@Component
public class AccountMapper {

    /**
     * This platform's own SWIFT/BIC, shared by every account rather than issued per account.
     *
     * <p>A BIC identifies the institution, the same way {@code DEFAULT_ROUTING_NUMBER} does.
     * Public because transaction-service must know which BIC belongs to this platform before it
     * will accept a wire aimed at one of these IBANs, and it reads the value through the internal
     * IBAN lookup instead of declaring a second copy. A wire carries an IBAN and a BIC that are
     * supposed to name the same bank; a duplicated constant that drifted would make that check
     * compare against the wrong one.
     *
     * <p>Format is a 4-character bank code plus a 2-character country code plus a 2-character
     * location code, which keeps the first six characters letters-only as
     * transaction-service's {@code IbanSwiftValidator} requires.
     */
    public static final String PLATFORM_SWIFT_CODE = "XBUSUS31";

    /**
     * Renders one account for its own owner.
     *
     * <p>The result carries the account number twice on purpose: masked for the dashboard's
     * at-a-glance view, and raw to back the Copy button on the Receive Money panel. That is only
     * safe because this DTO is built exclusively for the account's owner — never reuse it for a
     * response shown to a third party such as a payment recipient; use {@link #maskAccountNumber}
     * alone there.
     *
     * @param entity must be fully populated; a {@code null} account type, balance or status field
     *     fails here rather than producing a partial DTO
     * @return the overview DTO, never {@code null}
     */
    public AccountOverviewResponseDto toOverviewDto(AccountEntity entity) {
        return new AccountOverviewResponseDto(
                entity.getId(),
                entity.getAccountType().name(),
                entity.getAvailableBalance(),
                entity.getRoutingNumber(),
                maskAccountNumber(entity.getAccountNumber()),
                entity.getAccountNumber(),
                entity.getIban(),
                PLATFORM_SWIFT_CODE,
                entity.getStatus().name()
        );
    }

    /**
     * Replaces everything but the trailing four digits of an account number with dots.
     *
     * <p>Public so the internal recipient lookup, which masks a number without building a full
     * overview DTO, applies the identical rule instead of repeating it.
     *
     * <p>Degrades rather than throws: a {@code null} number, or one of four characters or fewer,
     * is returned unchanged, so a malformed row cannot break a response — but it also means short
     * input is not actually masked.
     *
     * @param rawAccountNumber the unmasked number; {@code null} and values of length four or less
     *     are passed straight back
     * @return the masked number, or the input unchanged when it is too short to mask
     */
    public String maskAccountNumber(String rawAccountNumber) {
        if (rawAccountNumber == null) {
            return rawAccountNumber;
        }
        if (rawAccountNumber.length() <= 4) {
            return rawAccountNumber;
        }

        int length = rawAccountNumber.length();
        String lastFourDigits = rawAccountNumber.substring(length - 4);

        String mask = ".".repeat(length - 4);

        return mask + lastFourDigits;
    }
}
