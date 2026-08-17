package com.example.profileservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Carries a daily-summary opt-in together with the timezone and hour it should be sent at.
 *
 * @param dailySummaryEnabled required; the opt-in has no default, so a request omitting it is
 *     rejected rather than treated as off
 * @param timezone required and non-blank, an IANA zone id such as {@code America/New_York} or
 *     {@code UTC}
 * @param dailySummaryHour whole hour {@code 0}-{@code 23} in {@code timezone}; deliberately optional
 *     — omitting it leaves the stored hour untouched rather than resetting it, so a client predating
 *     this field or one only flipping the toggle still submits successfully, while {@code 24} or
 *     {@code -1} is always rejected
 */
public record UpdateDailySummaryRequestDto(

        @NotNull(message = "Daily summary preference must be specified")
        Boolean dailySummaryEnabled,

        @NotBlank(message = "Timezone cannot be blank")
        String timezone,

        @Min(value = 0, message = "Daily summary hour must be between 0 and 23")
        @Max(value = 23, message = "Daily summary hour must be between 0 and 23")
        Integer dailySummaryHour

) {

    /**
     * Creates a request that changes the opt-in and timezone but leaves the send hour as stored.
     *
     * <p>The two-argument shape this record had before the hour existed, kept so that intent stays
     * expressible in one call. The omitted hour is recorded as unspecified — the same meaning as
     * leaving the field out of the JSON — not as a request to clear it.
     *
     * @param dailySummaryEnabled required, as in the canonical constructor
     * @param timezone required and non-blank, an IANA zone id
     */
    public UpdateDailySummaryRequestDto(Boolean dailySummaryEnabled, String timezone) {
        this(dailySummaryEnabled, timezone, null);
    }
}
