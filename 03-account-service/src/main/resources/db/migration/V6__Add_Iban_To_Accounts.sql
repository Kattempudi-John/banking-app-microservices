-- ===========================================================================
-- V6__Add_Iban_To_Accounts.sql
-- Purpose: Give every account a real per-account IBAN so it can be shared to receive
-- on-network transfers (see ExternalWireService in transaction-service, which now resolves
-- an incoming wire's IBAN against this column before treating it as a genuine external wire).
-- Nullable/unique rather than NOT NULL: existing rows predate this column and aren't backfilled,
-- but every account created from here on (UserRegisteredListener, AccountService.openAccount)
-- always sets one.
-- Numbered V6, not V3: account-service and transaction-service share the same local default
-- flyway_schema_history table/schema (see both services' application config), and versions 1-4
-- were already taken (account V1/V2, transaction V3/V4) before transaction-service's own
-- V5__Add_Wire_Destination_And_Identifiers.sql was added alongside this one.
-- ===========================================================================

ALTER TABLE accounts ADD COLUMN iban VARCHAR(34) UNIQUE;
