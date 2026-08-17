package com.example.profileservice.model;

/**
 * Where a customer stands in identity verification.
 *
 * <p>Only {@link #APPROVED} permits moving money; the other two are treated identically by the
 * gating checks, so a rejection is not more restrictive than a profile that has never been
 * submitted. Constants are persisted by name, so they must not be renamed without migrating the
 * stored rows.
 */
public enum KycStatus {

    /** Verification has not been completed; the starting state for every new profile. */
    PENDING_VERIFICATION,

    /** Identity was verified; the only state that permits money movement. */
    APPROVED,

    /** Verification failed or was revoked, by the vendor webhook or an admin override. */
    REJECTED
}