-- ===========================================================================
-- V8__Add_Idempotency_Key_To_Transactions.sql
-- Purpose: let a caller name a ledger write, so re-sending the same write applies it once.
-- transaction-service calls this service's internal credit/debit as a remote call from inside its
-- own local @Transactional block: if the credit succeeds but that outer commit then fails, the
-- retry replays a credit that already landed and the customer is paid twice. The key lives on the
-- ledger rather than in the caller so EVERY caller inherits the protection, not just the one path
-- where the double-apply was first noticed.
--
-- Numbered V8, not V3/V7 and not "next free number". account-service and transaction-service share
-- ONE flyway_schema_history table in the public schema locally, and transaction-service's migration
-- files are not on this module's classpath - hence the ignore-migration-patterns: "*:missing,*:future"
-- in this service's application.yml. Versions already spent: V1, V2, V6 here and V3, V4, V5, V7
-- there. Re-using any of those would either fail checksum validation or be silently skipped as
-- already-applied, leaving the column absent while Flyway reports success.
--
-- Nullable + UNIQUE rather than NOT NULL: Postgres treats NULLs as distinct under a unique index, so
-- unlimited rows may carry no key at all. That is what lets every existing row and every caller that
-- passes no key keep behaving exactly as before, while any two rows that DO name the same key
-- collide. VARCHAR over UUID because the key is the caller's opaque string, not ours to parse.
-- ===========================================================================

ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(255);

-- The database, not the application, is the final arbiter of "applied once". The service also checks
-- for an existing key before writing, but two concurrent retries can both pass that check before
-- either inserts - a classic check-then-act race that no amount of application code closes on its
-- own. This index is what makes the second insert fail rather than double-credit an account.
CREATE UNIQUE INDEX ux_transactions_idempotency_key ON transactions (idempotency_key);
