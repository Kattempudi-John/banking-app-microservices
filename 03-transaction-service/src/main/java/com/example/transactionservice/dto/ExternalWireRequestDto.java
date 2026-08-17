package com.example.transactionservice.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Carries the details of an outbound external wire request.
 *
 * <p>Bean validation on these components only checks shape. Whether an IBAN is real — the mod-97
 * checksum — is a separate check performed by {@code IbanSwiftValidator} inside the service, so a
 * payload that passes validation here can still be rejected with a 400 later.
 *
 * @param iban uppercase only, and structural: two letters, two digits, then 11 to 30 alphanumerics;
 *     must be non-blank, and the checksum is verified separately
 * @param swiftCode uppercase only: six letters, two alphanumerics, and an optional three-character
 *     branch code; must be non-blank
 * @param beneficiaryName non-blank, at most 100 characters, matching the column width it is stored
 *     in
 * @param amount required and strictly positive, floor 0.01, so zero is rejected rather than treated
 *     as a no-op; an amount over the service's fraud threshold is held for review instead of
 *     completing
 */
public record ExternalWireRequestDto(

        @NotBlank(message = "IBAN is mandatory")
        @Pattern(regexp = "^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$", message = "Invalid IBAN structure provided")
        String iban,

        @NotBlank(message = "SWIFT/BIC code is mandatory")
        @Pattern(regexp = "^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$", message = "Invalid SWIFT/BIC format provided")
        String swiftCode,

        @NotBlank(message = "Beneficiary name is mandatory")
        @Size(max = 100, message = "Beneficiary name must not exceed 100 characters")
        String beneficiaryName,

        @NotNull(message = "Transfer amount is mandatory")
        @Positive(message = "Transfer amount must be strictly greater than zero")
        @DecimalMin(value = "0.01", message = "Minimum transfer amount is 0.01")
        BigDecimal amount

) {}