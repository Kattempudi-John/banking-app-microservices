package com.example.profileservice.util;

import java.util.Optional;

import org.springframework.stereotype.Component;

// Mirrors auth-service's normalizer of the same name. Not extracted into a shared library because
// each service here is an independently-deployable module (the same shared-nothing reasoning the
// IbanGenerator/IbanSwiftValidator pair in account-service and transaction-service already follows).
//
// This service's copy of the phone number isn't what 2FA codes are sent to, and it is no longer
// edited here either - the identity form writes through to auth-service and stores the E.164 string
// that comes back (see ProfileManagementService). What's left for this class is historical rows:
// PhoneNumberBackfillRunner uses it to bring profiles provisioned before that write-through into the
// same E.164 shape, so the mirror doesn't display a number in a format the platform no longer uses.
@Component
public class PhoneNumberNormalizer {

    private static final String DEFAULT_COUNTRY_CALLING_CODE = "1";
    private static final int MIN_DIGITS = 8;
    private static final int MAX_DIGITS = 15;

    public Optional<String> normalize(String rawPhoneNumber) {
        if (rawPhoneNumber == null || rawPhoneNumber.isBlank()) {
            return Optional.empty();
        }

        String trimmed = rawPhoneNumber.trim();
        boolean explicitCountryCode = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("[^0-9]", "");

        if (digits.isEmpty()) {
            return Optional.empty();
        }

        String e164Digits;
        if (explicitCountryCode) {
            e164Digits = digits;
        } else if (digits.length() == 11 && digits.startsWith(DEFAULT_COUNTRY_CALLING_CODE)) {
            e164Digits = digits;
        } else if (digits.length() == 10) {
            e164Digits = DEFAULT_COUNTRY_CALLING_CODE + digits;
        } else {
            return Optional.empty();
        }

        if (e164Digits.length() < MIN_DIGITS || e164Digits.length() > MAX_DIGITS) {
            return Optional.empty();
        }

        return Optional.of("+" + e164Digits);
    }

    public boolean isValid(String rawPhoneNumber) {
        return normalize(rawPhoneNumber).isPresent();
    }
}
