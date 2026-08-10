package com.example.accountservice.util;

import java.math.BigInteger;

import org.springframework.stereotype.Component;

// Builds an IBAN out of an account's existing routing + account number, using the same ISO 7064
// mod-97 checksum that transaction-service's IbanSwiftValidator verifies against - this is just
// the generation side of that same math. Not shared as a library since each service here is an
// independently-deployable module (see the project's shared-nothing pattern elsewhere).
@Component
public class IbanGenerator {

    // Fictional country code - this is a demo bank, not a real financial institution.
    private static final String COUNTRY_CODE = "XB";

    public String generate(String routingNumber, String accountNumber) {
        String bban = routingNumber + accountNumber;
        String checkDigits = computeCheckDigits(bban);
        return COUNTRY_CODE + checkDigits + bban;
    }

    private String computeCheckDigits(String bban) {
        // Standard IBAN check-digit algorithm: rearrange with placeholder "00" check digits,
        // convert letters to numbers (A=10..Z=35), then check digits = 98 - (number mod 97).
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
