package com.example.profileservice.util;

import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Converts loosely formatted phone numbers into E.164, assuming a US country code when none is
 * given.
 *
 * <p>Deliberately duplicates auth-service's normalizer of the same name rather than being extracted
 * into a shared library, because each service is an independently deployable module — the same
 * shared-nothing reasoning behind the {@code IbanGenerator}/{@code IbanSwiftValidator} pair in
 * account-service and transaction-service. The consequence is that a rule change has to be applied
 * in both copies.
 *
 * <p>Its remaining use here is historical rows. This service's phone number is a mirror: 2FA codes
 * are not sent to it, and the identity form no longer edits it directly but writes through to
 * auth-service and stores the E.164 string that comes back. {@code PhoneNumberBackfillRunner} uses
 * this class to bring profiles provisioned before that write-through into the same shape, so the
 * mirror does not display a number in a format the platform no longer uses.
 */
@Component
public class PhoneNumberNormalizer {

    private static final String DEFAULT_COUNTRY_CALLING_CODE = "1";
    private static final int MIN_DIGITS = 8;
    private static final int MAX_DIGITS = 15;

    /**
     * Normalises a phone number to E.164, or reports that it cannot be resolved unambiguously.
     *
     * <p>A leading {@code +} is taken as an explicit country code and the digits are kept as given.
     * Without one, only two shapes are accepted: 10 digits, which gets {@code 1} prefixed, and 11
     * digits already starting with {@code 1}. Any other length is refused rather than guessed at, so
     * a non-US number typed without its {@code +} is rejected instead of being silently turned into
     * a US number. The result must total 8 to 15 digits, the E.164 bounds.
     *
     * @param rawPhoneNumber may be {@code null} or blank, both of which yield an empty result rather
     *     than throwing; separators, spaces, parentheses and dots are discarded before validation
     * @return the {@code +}-prefixed E.164 string, or empty when no single valid number can be
     *     derived
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
     * Reports whether a number can be normalised.
     *
     * <p>Exactly the success test of {@link #normalize(String)}; when the normalised value is also
     * wanted, call that instead of testing first and converting after.
     *
     * @param rawPhoneNumber may be {@code null} or blank, both of which are invalid
     * @return {@code true} only when an unambiguous E.164 number can be derived
     */
    public boolean isValid(String rawPhoneNumber) {
        return normalize(rawPhoneNumber).isPresent();
    }
}
