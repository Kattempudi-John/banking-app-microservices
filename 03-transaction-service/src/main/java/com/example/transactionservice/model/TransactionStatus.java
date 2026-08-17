package com.example.transactionservice.model;

/**
 * Tracks where a wire sits in fraud review.
 *
 * <p>Not to be confused with account-service's {@code TransactionType} ({@code CREDIT} /
 * {@code DEBIT}), which classifies the direction of a ledger movement. This enum records review
 * state only, and is persisted by name, so renaming a constant breaks existing rows.
 */
public enum TransactionStatus {

    /** Funds have moved; either the wire was under the fraud threshold or review approved it. */
    COMPLETED,

    /**
     * Held for fraud review because the amount exceeded the threshold; the only state from which
     * the internal fraud-status endpoint will act.
     */
    PENDING_APPROVAL,

    /** Review declined the wire and the held funds were returned. */
    REJECTED,

    /**
     * Never assigned anywhere in this service. No code path sets it and no row can hold it; it
     * exists only as a declared constant.
     */
    FAILED
}
