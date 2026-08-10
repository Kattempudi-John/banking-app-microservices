package com.example.accountservice.mapper;

import org.springframework.stereotype.Component;

import com.example.accountservice.dto.AccountOverviewResponseDto;
import com.example.accountservice.model.AccountEntity;

// separating this mapping logic into its own @component instead of stuffing it into the service
// or the entity keeps the entity to db mapping and the entity to api response mapping independent
@Component
public class AccountMapper {

    // SWIFT/BIC identifies the institution, not the individual account - every account here
    // shares this one value, the same way they all share DEFAULT_ROUTING_NUMBER.
    // Format: 4-char bank code + 2-char country code (6 letters total) + 2-char location code -
    // matches transaction-service's IbanSwiftValidator/ExternalWireRequestDto validation, which
    // requires the first 6 characters to be letters only.
    private static final String PLATFORM_SWIFT_CODE = "XBUSUS31";

    public AccountOverviewResponseDto toOverviewDto(AccountEntity entity) {
        return new AccountOverviewResponseDto(
                entity.getId(),
                entity.getAccountType().name(),
                entity.getAvailableBalance(),
                entity.getRoutingNumber(),
                maskAccountNumber(entity.getAccountNumber()),
                entity.getIban(),
                PLATFORM_SWIFT_CODE,
                entity.getStatus().name()
        );
    }

    // Public because InternalAccountController's recipient lookup masks a number it didn't build a
    // full overview DTO for - same masking rule, so it reuses this rather than repeating it.
    public String maskAccountNumber(String rawAccountNumber) {
        if (rawAccountNumber == null) {
            return rawAccountNumber; // Failsafe for unusually short or malformed numbers
        }
        if (rawAccountNumber.length() <= 4) {
            return rawAccountNumber; // Failsafe for unusually short or malformed numbers
        }

        int length = rawAccountNumber.length();
        String lastFourDigits = rawAccountNumber.substring(length - 4);

        // Creates a string of dots for the hidden portion
        // learned string.repeat is a pretty recent java addition, used to have to build this
        // kind of padding with a loop or stringbuilder before it existed
        String mask = ".".repeat(length - 4);

        return mask + lastFourDigits;
    }
}
