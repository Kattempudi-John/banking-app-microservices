package com.example.authservice.util;

import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Converts a phone number as a person would type it into the E.164 form the SMS provider requires.
 *
 * <p>Twilio accepts only E.164 destinations ({@code "+15712856947"}) and rejects anything else
 * outright, so a number stored as {@code "571-285-6947"} or {@code "(571) 285-6947"} means the 2FA
 * code is never delivered and the login simply appears to hang. Normalizing at the point of entry
 * keeps every stored number sendable instead of leaving each consumer to guess at the format. It is
 * also what makes the unique constraint on {@code User.phoneNumber} meaningful, since two spellings
 * of one number are two different strings to the database.
 */
@Component
public class PhoneNumberNormalizer {

    private static final String DEFAULT_COUNTRY_CALLING_CODE = "1";

    private static final int MIN_DIGITS = 8;
    private static final int MAX_DIGITS = 15;

    /**
     * Normalizes a human-entered phone number to E.164, or reports that it cannot be.
     *
     * <p>Separators a person might type — spaces, dashes, parentheses, dots — are stripped. A
     * number that already carries a {@code "+"} keeps its own country code and passes through
     * untouched. A bare ten-digit number is assumed North American and gets {@code +1}: this is a
     * US-only deployment, so that assumption is unambiguous here and would not be elsewhere.
     * Anything that cannot be resolved to exactly one number — seven-digit local numbers,
     * extensions, typos, or a result outside 8 to 15 digits — is rejected rather than guessed at,
     * because guessing produces a valid address belonging to someone else.
     *
     * @param rawPhoneNumber may be {@code null} or blank, both of which yield empty rather than
     *     throwing
     * @return empty when the input cannot be turned into a valid E.164 number; callers decide
     *     whether that is a validation error (registration) or a reason to leave an existing row
     *     untouched (the backfill)
     */
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

    /**
     * Reports whether a number can be normalized to E.164.
     *
     * <p>Convenience over {@link #normalize}, and answers exactly the same question. Prefer
     * {@code normalize} when the normalized value will be needed anyway, since calling both
     * normalizes twice.
     *
     * @param rawPhoneNumber may be {@code null} or blank, both of which are invalid rather than
     *     errors
     * @return {@code true} only when the input resolves to one unambiguous number
     */
    public boolean isValid(String rawPhoneNumber) {
        return normalize(rawPhoneNumber).isPresent();
    }
}
