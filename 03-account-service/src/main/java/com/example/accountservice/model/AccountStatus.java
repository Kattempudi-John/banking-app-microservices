package com.example.accountservice.model;

/**
 * Lifecycle state of an account, persisted by name into the {@code account_status_enum} column.
 *
 * <p>Stored as a string, so renaming a constant rewrites the meaning of existing rows and requires a
 * migration. {@code CLOSED} is the value excluded from balance aggregation and from the account list
 * returned to a user; {@code FROZEN} accounts are still listed and still counted.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
