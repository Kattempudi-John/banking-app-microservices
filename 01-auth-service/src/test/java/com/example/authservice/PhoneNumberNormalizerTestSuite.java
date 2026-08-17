package com.example.authservice;

import com.example.authservice.util.PhoneNumberNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PhoneNumberNormalizer}, the conversion from a phone number as a person types
 * it into the E.164 form the SMS provider requires.
 *
 * <p><strong>Slice:</strong> none. This is plain JUnit 5 with no Spring context, no slice
 * annotation, no mocks and no database — the normalizer is a stateless {@code @Component} with no
 * collaborators, so it is constructed directly with {@code new} and exercised as an ordinary object.
 * That keeps the whole class a few milliseconds of pure computation rather than a context boot, and
 * it is the reason nothing here needs {@code @MockBean}: there is nothing to stand in for.
 *
 * <p><strong>Fixture state:</strong> the single {@code normalizer} field is a {@code final}
 * initializer rather than a {@code @BeforeEach} setup. JUnit builds a fresh instance of this class
 * per test method, so each test gets its own normalizer; because the normalizer holds no mutable
 * state, no test can influence another and the order they run in is irrelevant.
 *
 * <p><strong>Why the output shape matters:</strong> the SMS provider rejects any destination that is
 * not E.164, and a rejected send is invisible to the user — the login simply appears never to
 * complete. So these assertions on an exact string are load-bearing for 2FA working at all, not
 * cosmetic formatting checks.
 *
 * <p><strong>Reading the fixture values:</strong> the numbers here are chosen to sit on the
 * boundaries the normalizer enforces rather than being arbitrary.
 * <ul>
 *   <li>Rejection is bounded at 8 and 15 digits. 15 is E.164's hard cap on a subscriber number, so
 *       anything longer cannot be a real number; 8 is the shortest length a genuine international
 *       number reaches, so anything shorter is a local fragment rather than a dialable address.</li>
 *   <li>A bare ten-digit number is assumed North American and given {@code +1}. That assumption is
 *       deliberate, not an oversight: this is a US-only deployment, which is the only setting where
 *       a ten-digit number resolves to exactly one country.</li>
 *   <li>Ambiguous input is rejected outright rather than guessed at, because a guess still produces
 *       a syntactically valid number — belonging to someone else, who would then receive the 2FA
 *       code.</li>
 * </ul>
 */
class PhoneNumberNormalizerTestSuite {

    private final PhoneNumberNormalizer normalizer = new PhoneNumberNormalizer();

    /**
     * The dashed form is what a person actually types, and what was sitting in the database before
     * registration started normalizing its input — so this is the shape the backfill has to handle.
     */
    @Test
    @DisplayName("Normalizes a dashed US number to E.164 - [MEANT TO PASS]")
    void normalize_dashedUsNumber_returnsE164() {
        Optional<String> normalized = normalizer.normalize("571-285-6947");

        assertThat(normalized).contains("+15712856947");
    }

    @Test
    @DisplayName("Normalizes parentheses, dots and spaces - [MEANT TO PASS]")
    void normalize_parenthesesDotsOrSurroundingSpaces_returnsE164() {
        assertThat(normalizer.normalize("(571) 285-6947")).contains("+15712856947");
        assertThat(normalizer.normalize("571.285.6947")).contains("+15712856947");
        assertThat(normalizer.normalize("  571 285 6947  ")).contains("+15712856947");
    }

    @Test
    @DisplayName("Adds the missing plus to a country-coded number - [MEANT TO PASS]")
    void normalize_elevenDigitsCarryingCountryCodeWithoutPlus_addsThePlus() {
        Optional<String> normalized = normalizer.normalize("15712856947");

        assertThat(normalized).contains("+15712856947");
    }

    /**
     * An already-normalized value has to survive untouched. If it did not, the backfill that sweeps
     * existing rows would rewrite the good ones as well as the bad.
     */
    @Test
    @DisplayName("Leaves an already-normalized number unchanged - [MEANT TO PASS]")
    void normalize_inputAlreadyInE164_returnsItUnchanged() {
        Optional<String> normalized = normalizer.normalize("+15712856947");

        assertThat(normalized).contains("+15712856947");
    }

    /**
     * The {@code +1} default applies only to bare ten-digit input; a number that states its own
     * country code keeps it rather than being forced onto the US code.
     */
    @Test
    @DisplayName("Preserves a non-US country code - [MEANT TO PASS]")
    void normalize_numberWithExplicitNonUsCountryCode_keepsThatCountryCode() {
        Optional<String> normalized = normalizer.normalize("+44 20 7946 0958");

        assertThat(normalized).contains("+442079460958");
    }

    @Test
    @DisplayName("Rejects input it cannot resolve to one unambiguous number - [MEANT TO FAIL]")
    void normalize_ambiguousOrOutOfRangeInput_returnsEmpty() {
        assertThat(normalizer.normalize("285-6947")).isEmpty();      // 7 digits, no area code
        assertThat(normalizer.normalize("12345")).isEmpty();         // too short
        assertThat(normalizer.normalize("not a phone")).isEmpty();   // no digits at all
        assertThat(normalizer.normalize("+1234567890123456")).isEmpty(); // past E.164's 15-digit cap
    }

    /**
     * Absent input is not the same as bad input: both yield empty, but neither throws, so a caller
     * sweeping rows with no phone number on file does not have to guard every call.
     */
    @Test
    @DisplayName("Treats null and blank as absent rather than invalid - [MEANT TO PASS]")
    void normalize_nullOrBlankInput_returnsEmptyWithoutThrowing() {
        assertThat(normalizer.normalize(null)).isEqualTo(Optional.empty());
        assertThat(normalizer.normalize("   ")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("isValid returns true for a normalizable number and false otherwise - [MEANT TO PASS]")
    void isValid_normalizableAndUnnormalizableInput_returnsTrueThenFalse() {
        assertThat(normalizer.isValid("571-285-6947")).isTrue();
        assertThat(normalizer.isValid("nope")).isFalse();
    }
}
