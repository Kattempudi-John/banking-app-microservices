package com.example.profileservice.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Carries the stored contact and identity fields back to the identity form.
 *
 * <p>The body of {@code GET /profiles/me/contact-info}, which exists so the form pre-fills instead
 * of opening blank. A blank form is what led users to retype a phone number different from the one
 * they registered with, and so to two accounts sharing a number.
 *
 * <p>Every component is nullable: a profile that has never completed the form has none of them and
 * the form must still render.
 *
 * @param legalName {@code null} until the identity form is first submitted
 * @param dateOfBirth serialised as {@code yyyy-MM-dd}, matching both the request DTO and what a
 *     native {@code <input type="date">} expects, so the value round-trips through the form
 *     untouched; {@code null} until first submission
 * @param phoneNumber read from auth-service, the owner of this field, rather than this service's
 *     mirror copy; E.164 when present, {@code null} when auth-service holds none
 * @param addressLine1 {@code null} until first submission
 * @param addressLine2 optional even on a completed profile, so {@code null} is not evidence the form
 *     was skipped
 * @param city {@code null} until first submission
 * @param state {@code null} until first submission
 * @param zipCode {@code null} until first submission
 */
public record ContactInfoResponseDto(
        String legalName,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate dateOfBirth,
        String phoneNumber,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String zipCode) {
}
