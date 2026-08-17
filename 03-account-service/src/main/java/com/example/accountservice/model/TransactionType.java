package com.example.accountservice.model;

/**
 * Direction of a ledger movement, persisted by name into the {@code transaction_type_enum} column.
 *
 * <p>{@code CREDIT} increases the account balance and {@code DEBIT} decreases it; the amount on a
 * transaction is always positive, so this constant is the only thing carrying the sign. Stored as a
 * string, so renaming a constant rewrites the meaning of existing rows and requires a migration.
 */
public enum TransactionType {
    CREDIT,
    DEBIT
}