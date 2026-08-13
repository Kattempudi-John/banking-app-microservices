package com.example.profileservice.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UpdateContactInfoRequestDto {

    // Identity fields. These are what make submitting this form a verification rather than a contact
    // update - a name and date of birth checked against an address is roughly what a real KYC vendor
    // is given. Required, because a partially completed identity is not something that could ever be
    // approved, and completing this form is exactly what triggers approval.
    @NotBlank(message = "Full legal name is required")
    @Size(max = 255, message = "Full legal name cannot exceed 255 characters")
    private String legalName;

    // ISO yyyy-MM-dd, which is what the browser's native <input type="date"> submits, so the form
    // needs no date parsing of its own. @Past rejects today and the future; the 18+ rule is enforced
    // in ProfileManagementService rather than here because a bean-validation annotation cannot
    // express "at least 18 years before now" without a custom validator.
    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateOfBirth;

    // Accepts the separators a person actually types - "(571) 285-6947", "571-285-6947",
    // "+1 571 285 6947" - and is forwarded to auth-service exactly as typed, which converts it to
    // E.164 and checks nobody else already holds it. This pattern only rejects input that clearly
    // isn't a phone number at all; auth-service makes the real call, and ProfileManagementService
    // passes its error back if it can't resolve one unambiguous number.
    // learned all these jakarta validation annotations only actually run when the controller
    // method parameter is also marked @Valid, the annotation alone on the dto does nothing by itself
    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[+()\\-.\\s0-9]{7,20}$", message = "Phone number must be a valid phone number")
    private String phoneNumber;

    @NotBlank(message = "Address line 1 is required")
    @Size(max = 255, message = "Address line 1 cannot exceed 255 characters")
    private String addressLine1;

    // Optional field, so no @NotBlank constraint
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

    // --- Getters and Setters ---

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