package com.example.transactionservice.util;

import java.math.BigInteger;

import org.springframework.stereotype.Component;

/**
 * Checks the shape of the IBAN and SWIFT/BIC pair a sender types on an outgoing wire.
 *
 * <p>Both checks are self-contained string validation. Neither one can tell whether the account
 * exists, whether the two identifiers name the same bank, or whether anyone is willing to receive
 * the money; those questions belong to account-service and to the caller.
 */
@Component
public class IbanSwiftValidator {

    private static final String SWIFT_REGEX = "^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$";

    /**
     * Reports whether an IBAN is well formed and satisfies its mod-97 check digits.
     *
     * <p>The value is normalised first — every whitespace character removed, then upper-cased — so
     * a number typed in the usual groups of four, or in lower case, is accepted as typed. After
     * normalisation the length must fall between 15 and 34 characters and every character must be
     * alphanumeric.
     *
     * <p>Because mod-97 is a real check digit, a single mistyped character is caught here. That is
     * the strongest guarantee in this class, and the reason the destination bank is resolved from
     * the IBAN rather than from the accompanying BIC.
     *
     * @param iban raw user input, unnormalised; {@code null} and malformed values return
     *     {@code false} rather than throwing
     * @return {@code true} only when length, alphabet, and a mod-97 remainder of exactly 1 all hold
     */
    public boolean isValidIban(String iban) {
        if (iban == null) {
            return false;
        }

        String normalizedIban = iban.replaceAll("\\s+", "").toUpperCase();

        if (normalizedIban.length() < 15) {
            return false;
        }
        if (normalizedIban.length() > 34) {
            return false;
        }

        String rearrangedIban = normalizedIban.substring(4) + normalizedIban.substring(0, 4);

        StringBuilder numericIban = new StringBuilder();
        for (int i = 0; i < rearrangedIban.length(); i++) {
            char ch = rearrangedIban.charAt(i);
            if (Character.isLetter(ch)) {
                numericIban.append(Character.getNumericValue(ch));
            } else if (Character.isDigit(ch)) {
                numericIban.append(ch);
            } else {
                return false;
            }
        }

        try {
            BigInteger ibanNumber = new BigInteger(numericIban.toString());
            return ibanNumber.remainder(new BigInteger("97")).intValue() == 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Reports whether a SWIFT/BIC code has a legal shape.
     *
     * <p>Shape only. A BIC carries no check digit, so any well-formed string passes and one wrong
     * character produces another equally valid code naming a different bank. A caller that needs
     * the BIC to agree with the IBAN beside it must compare the submitted value against the
     * holding bank's own code; this method cannot detect that mismatch.
     *
     * <p>The value is trimmed and upper-cased before matching, which is the same normalisation
     * {@code ExternalWireService} applies before its own comparison — keep the two in step or a
     * correctly typed lower-case BIC will pass here and be rejected there.
     *
     * @param swiftCode raw user input, unnormalised; {@code null} returns {@code false} rather than
     *     throwing
     * @return {@code true} for exactly 8 characters (head office) or 11 (specific branch) matching
     *     the BIC pattern; any other length is rejected before the pattern is applied
     */
    public boolean isValidSwift(String swiftCode) {
        if (swiftCode == null) {
            return false;
        }

        String normalizedSwift = swiftCode.trim().toUpperCase();

        boolean isValidLength = normalizedSwift.length() == 8 || normalizedSwift.length() == 11;
        if (!isValidLength) {
            return false;
        }

        return normalizedSwift.matches(SWIFT_REGEX);
    }
}
