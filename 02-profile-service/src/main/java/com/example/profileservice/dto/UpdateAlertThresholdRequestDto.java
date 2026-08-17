package com.example.profileservice.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Carries a new large-transaction alert threshold.
 *
 * <p>Constraints are enforced only when the controller parameter is also marked {@code @Valid};
 * without it these annotations are inert and an invalid amount reaches the service layer.
 *
 * @param alertThresholdAmount required and strictly positive, minimum {@code 0.01}, so disabling
 *     alerts cannot be expressed by sending zero
 */
public record UpdateAlertThresholdRequestDto(

        @NotNull(message = "Alert threshold cannot be null")
        @Positive(message = "Threshold must be a positive amount")
        @DecimalMin(value = "0.01", message = "Minimum threshold is 0.01")
        BigDecimal alertThresholdAmount

) {}
