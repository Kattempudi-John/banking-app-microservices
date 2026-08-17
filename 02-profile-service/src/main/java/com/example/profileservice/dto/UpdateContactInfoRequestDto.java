package com.example.profileservice.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Carries an identity submission: the contact fields plus the name and date of birth that make it a
 * verification rather than a contact update.
 *
 * <p>A name and date of birth checked against an address is roughly what a real KYC vendor is given,
 * and submitting this form is what triggers approval — which is why the identity fields are required
 * rather than optional. A partially completed identity is not something that could ever be approved.
 *
 * <p>Field constraints, all enforced only when the controller parameter is also marked
 * {@code @Valid}:
 *
 * <ul>
 *   <li>{@code legalName} — required, max 255 characters
 *   <li>{@code dateOfBirth} — required, {@code yyyy-MM-dd}, strictly in the past so today is
 *       rejected. The 18+ rule is <em>not</em> checked here; {@code ProfileManagementService}
 *       enforces it, because bean validation cannot express "at least 18 years ago" without a custom
 *       validator
 *   <li>{@code phoneNumber} — required. The pattern admits the separators a person actually types
 *       ({@code (571) 285-6947}, {@code 571-285-6947}, {@code +1 571 285 6947}) and only rejects
 *       input that is plainly not a phone number. It is forwarded to auth-service exactly as typed;
 *       auth-service converts it to E.164, checks nobody else holds it, and its error is passed back
 *       when no single unambiguous number can be resolved
 *   <li>{@code addressLine1} — required, max 255 characters
 *   <li>{@code addressLine2} — optional, max 255 characters
 *   <li>{@code city} — required, max 100 characters
 *   <li>{@code state} — required, max 50 characters
 *   <li>{@code zipCode} — required, max 20 characters
 * </ul>
 */
public class UpdateContactInfoRequestDto {

    @NotBlank(message = "Full legal name is required")
    @Size(max = 255, message = "Full legal name cannot exceed 255 characters")
    private String legalName;

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateOfBirth;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[+()\\-.\\s0-9]{7,20}$", message = "Phone number must be a valid phone number")
    private String phoneNumber;

    @NotBlank(message = "Address line 1 is required")
    @Size(max = 255, message = "Address line 1 cannot exceed 255 characters")
    private String addressLine1;

    @Size(max = 255, message = "Address line 2 cannot exceed 255 characters")
    private String addressLine2;

    @NotBlank(message = "City is required")
    @Size(max = 100, message = "City cannot exceed 100 characters")
    private String city;

    @NotBlank(message = "State/Province is required")
    @Size(max = 50, message = "State/Province cannot exceed 50 characters")
    private String state;

    @NotBlank(message = "Zip/Postal code is required")
    @Size(max = 20, message = "Zip/Postal code cannot exceed 20 characters")
    private String zipCode;

    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String addressLine1) { this.addressLine1 = addressLine1; }

    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String addressLine2) { this.addressLine2 = addressLine2; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getZipCode() { return zipCode; }
    public void setZipCode(String zipCode) { this.zipCode = zipCode; }
}