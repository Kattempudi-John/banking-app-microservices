package com.example.authservice.util;

import java.util.Optional;

import org.springframework.stereotype.Component;

// Twilio only accepts destination numbers in E.164 ("+15712856947") and rejects anything else
// outright, so a number stored the way a person would naturally type it - "571-285-6947",
// "(571) 285-6947" - means the 2FA code is never delivered and the login just appears to hang.
// This normalizes at the point of entry so the stored value is always sendable, rather than
// leaving every consumer to guess at the format.
@Component
public class PhoneNumberNormalizer {

    // This is a US-only demo bank (see DEFAULT_ROUTING_NUMBER in account-service and the XBUSUS31
    // SWIFT code), so a bare 10-digit number is unambiguously North American. A number that already
    // carries a "+" prefix is left on its own country code and passes through untouched.
    private static final String DEFAULT_COUNTRY_CALLING_CODE = "1";

    // E.164 caps the whole number at 15 digits; 8 is a floor that still admits the shortest real
    // international numbers while rejecting obvious junk.
    private static final int MIN_DIGITS = 8;
    private static final int MAX_DIGITS = 15;

    // Empty when the input can't be turned into a valid E.164 number. Callers decide whether that's
    // a validation error (registration) or a reason to leave an existing row alone (the backfill).
    public Optional<String> normalize(String rawPhoneNumber) {
        if (rawPhoneNumber == null || rawPhoneNumber.isBlank()) {
            return Optional.empty();
        }

        String trimmed = rawPhoneNumber.trim();
        boolean explicitCountryCode = trimmed.startsWith("+");

        // Drop everything a human might use as separators - spaces, dashes, parentheses, dots.
        String digits = trimmed.replaceAll("[^0-9]", "");

        if (digits.isEmpty()) {
            return Optional.empty();
        }

        String e164Digits;
        if (explicitCountryCode) {
            e164Digits = digits;
        } else if (digits.length() == 11 && digits.startsWith(DEFAULT_COUNTRY_CALLING_CODE)) {
            // "15712856947" - already country-coded, just missing the plus.
            e164Digits = digits;
        } else if (digits.length() == 10) {
            // "5712856947" - a bare US number.
            e164Digits = DEFAULT_COUNTRY_CALLING_CODE + digits;
        } else {
            // Anything else (7-digit local numbers, extensions, typos) can't be resolved to a single
            // unambiguous number, and guessing would produce an address that silently belongs to
            // someone else.
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
