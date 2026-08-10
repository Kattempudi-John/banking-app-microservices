package com.example.authservice;

import com.example.authservice.util.PhoneNumberNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// The SMS provider rejects anything that isn't E.164, and a rejected send looks to the user like a
// login that simply never completes - so the exact output shape here is what makes 2FA work at all.
class PhoneNumberNormalizerTestSuite {

    private final PhoneNumberNormalizer normalizer = new PhoneNumberNormalizer();

    // the format a person actually types, which is what was sitting in the database before
    // registration normalized its input
    @Test
    @DisplayName("Normalizes a dashed US number to E.164 - [MEANT TO PASS]")
    void testDashedUsNumber() {
        assertThat(normalizer.normalize("571-285-6947")).contains("+15712856947");
    }

    @Test
    @DisplayName("Normalizes parentheses, dots and spaces - [MEANT TO PASS]")
    void testOtherSeparators() {
        assertThat(normalizer.normalize("(571) 285-6947")).contains("+15712856947");
        assertThat(normalizer.normalize("571.285.6947")).contains("+15712856947");
        assertThat(normalizer.normalize("  571 285 6947  ")).contains("+15712856947");
    }

    // 11 digits already carrying the US country code, just missing the plus
    @Test
    @DisplayName("Adds the missing plus to a country-coded number - [MEANT TO PASS]")
    void testCountryCodedWithoutPlus() {
        assertThat(normalizer.normalize("15712856947")).contains("+15712856947");
    }

    // anything already in E.164 has to survive untouched, or the backfill would rewrite good rows
    @Test
    @DisplayName("Leaves an already-normalized number unchanged - [MEANT TO PASS]")
    void testAlreadyE164() {
        assertThat(normalizer.normalize("+15712856947")).contains("+15712856947");
    }

    // a non-US number keeps its own country code rather than being forced to +1
    @Test
    @DisplayName("Preserves a non-US country code - [MEANT TO PASS]")
    void testInternationalNumber() {
        assertThat(normalizer.normalize("+44 20 7946 0958")).contains("+442079460958");
    }

    // guessing at these would point a 2FA code at somebody else's phone, so they're refused outright
    @Test
    @DisplayName("Rejects input it cannot resolve to one unambiguous number - [MEANT TO FAIL]")
    void testUnresolvableInput() {
        assertThat(normalizer.normalize("285-6947")).isEmpty();      // 7 digits, no area code
        assertThat(normalizer.normalize("12345")).isEmpty();         // too short
        assertThat(normalizer.normalize("not a phone")).isEmpty();   // no digits at all
        assertThat(normalizer.normalize("+1234567890123456")).isEmpty(); // past E.164's 15-digit cap
    }

    @Test
    @DisplayName("Treats null and blank as absent rather than invalid - [MEANT TO PASS]")
    void testNullAndBlank() {
        assertThat(normalizer.normalize(null)).isEqualTo(Optional.empty());
        assertThat(normalizer.normalize("   ")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("isValid agrees with normalize - [MEANT TO PASS]")
    void testIsValid() {
        assertThat(normalizer.isValid("571-285-6947")).isTrue();
        assertThat(normalizer.isValid("nope")).isFalse();
    }
}
