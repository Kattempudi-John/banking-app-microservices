package com.example.profileservice.model;

import java.time.LocalDate;

import jakarta.persistence.*;

/**
 * A customer's contact details, identity fields, and KYC standing.
 *
 * <p>The identifier is <em>not</em> generated: it is assigned from the user's auth-service id, so a
 * profile saved without one first is rejected rather than given a fresh key. That shared id is what
 * lets other services look a profile up without a second lookup by email.
 *
 * <p>{@code email} is copied from the {@code user-events} registration message rather than fetched
 * from auth-service on each send — notification-service already calls this service for a user's
 * alert preferences, so carrying the address on that response costs nothing extra.
 *
 * <p>{@code legalName} and {@code dateOfBirth} are the identity half of KYC: an address alone
 * verifies nothing, so they are collected with the contact fields and are what gates automatic
 * approval in {@code ProfileManagementService}. Both are nullable, because every profile created
 * before they existed has neither and those users stay {@link KycStatus#PENDING_VERIFICATION} until
 * they complete the form.
 *
 * <p>The status is persisted as its name rather than its ordinal, so reordering the enum constants
 * cannot silently reinterpret stored rows.
 */
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    private Long id;

    private String phoneNumber;
    private String email;

    private String legalName;
    private LocalDate dateOfBirth;

    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String zipCode;

    @Enumerated(EnumType.STRING)
    private KycStatus kycStatus = KycStatus.PENDING_VERIFICATION;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

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

    public KycStatus getKycStatus() { return kycStatus; }
    public void setKycStatus(KycStatus kycStatus) { this.kycStatus = kycStatus; }
}