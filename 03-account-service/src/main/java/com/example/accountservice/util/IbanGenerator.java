package com.example.accountservice.util;

import java.math.BigInteger;

import org.springframework.stereotype.Component;

/**
 * Derives an IBAN from an account's existing routing and account numbers.
 *
 * <p>Produces the generation side of the ISO 7064 mod-97 checksum that transaction-service's
 * {@code IbanSwiftValidator} verifies against. Kept here rather than in a shared library because
 * each service in this project is an independently deployable module.
 *
 * <p>Issues IBANs under the fictional country code {@code XB}; this is a demo bank, not a
 * registered institution, so the values are well-formed but not routable.
 */
@Component
public class IbanGenerator {

    private static final String COUNTRY_CODE = "XB";

    /**
     * Builds the IBAN for one account.
     *
     * <p>Deterministic: the same inputs always yield the same IBAN, which is what lets
     * {@code IbanBackfillRunner} fill in historical rows without changing what a re-run produces.
     *
     * @param routingNumber digits only, concatenated ahead of the account number to form the BBAN;
     *     a value that differs from the one stored on the account produces an IBAN that will not
     *     match that account
     * @param accountNumber digits only; must be the account's raw number, not a masked form
     * @return the full IBAN, {@code XB} plus two check digits plus the BBAN, never {@code null}
     */
    public String generate(String routingNumber, String accountNumber) {
        String bban = routingNumber + accountNumber;
        String checkDigits = computeCheckDigits(bban);
        return COUNTRY_CODE + checkDigits + bban;
    }

    private String computeCheckDigits(String bban) {
        String rearranged = bban + COUNTRY_CODE + "00";
        StringBuilder numeric = new StringBuilder();
        for (char ch : rearranged.toCharArray()) {
            if (Character.isLetter(ch)) {
                numeric.append(Character.getNumericValue(ch));
            } else {
                numeric.append(ch);
            }
        }

        int remainder = new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue();
        int checkDigits = 98 - remainder;
        return String.format("%02d", checkDigits);
    }
}
