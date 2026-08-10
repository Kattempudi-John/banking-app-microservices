-- ===========================================================================
-- V7__Add_Created_At_To_Wire_Transactions.sql
-- Purpose: wire_transactions has never had a timestamp column - the new GET /api/v1/transfers
-- history endpoint needs one to sort/filter by date, same as account-service's transactions table
-- already has.
-- Numbered V7: account-service and transaction-service share the same local default
-- flyway_schema_history table/schema (see both services' application config comments) - versions
-- 1-6 were already taken (account V1/V2/V6, transaction V3/V4/V5) before this one.
-- ===========================================================================

ALTER TABLE wire_transactions ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
