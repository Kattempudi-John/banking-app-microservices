package com.example.profileservice.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

// What GET /profiles/me/contact-info answers with, so the identity form can pre-fill instead of
// opening blank. A blank form is what caused users to retype a different phone number than the one
// they registered with, which is how two accounts ended up sharing a number in the first place.
//
// Every field is nullable on purpose: a profile that has never completed the form has none of them,
// and the form still has to render.
public record ContactInfoResponseDto(
        String legalName,
        // Matches the yyyy-MM-dd the request DTO accepts, which is also what the browser's native
        // <input type="date"> expects - so the value round-trips through the form untouched.
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate dateOfBirth,
        // Read from auth-service, the owner of this field, not from this service's mirror copy.
        String phoneNumber,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String zipCode) {
}
