package com.example.profileservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateDailySummaryRequestDto(

        @NotNull(message = "Daily summary preference must be specified")
        Boolean dailySummaryEnabled,

        @NotBlank(message = "Timezone cannot be blank")
        // We expect standard IANA timezone formats like "America/New_York" or "UTC"
        String timezone,

        // Whole hours in the user's own timezone above. Deliberately NOT @NotNull: a client that
        // predates this field (or a caller only flipping the toggle) leaves the hour it omitted
        // exactly as it was, rather than being refused or having it silently reset to the default.
        // @Min/@Max ignore a null, so that stays a valid submission while 24 or -1 never does.
        @Min(value = 0, message = "Daily summary hour must be between 0 and 23")
        @Max(value = 23, message = "Daily summary hour must be between 0 and 23")
        Integer dailySummaryHour

) {

    // The two-argument form this record had before the hour existed, kept so "set the toggle and
    // timezone, leave the hour alone" stays expressible in one call. Passing null here is the same
    // "hour not specified" the JSON omission means, not a request to clear it.
    public UpdateDailySummaryRequestDto(Boolean dailySummaryEnabled, String timezone) {
        this(dailySummaryEnabled, timezone, null);
    }
}
