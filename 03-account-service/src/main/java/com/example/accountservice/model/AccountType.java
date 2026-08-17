package com.example.accountservice.model;

/**
 * Product kind of an account, persisted by name into the {@code account_type_enum} column.
 *
 * <p>Stored as a string, so renaming a constant rewrites the meaning of existing rows and requires a
 * migration.
 */
public enum AccountType {
    CHECKING,
    SAVINGS
}