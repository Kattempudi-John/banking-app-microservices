-- ===========================================================================
-- V5__Add_Wire_Destination_And_Identifiers.sql
-- Purpose: Persist the iban/swift/beneficiary a wire request carries (previously dropped after
-- validation, only folded into the free-text description), plus a nullable destination_account_id
-- for "on-us" wires whose IBAN resolves to a real account on this platform (see
-- ExternalWireService/FraudResolutionService) - genuinely external wires leave this null exactly
-- like today.
-- ===========================================================================

ALTER TABLE wire_transactions
    ADD COLUMN iban VARCHAR(34),
    ADD COLUMN swift_code VARCHAR(11),
    ADD COLUMN beneficiary_name VARCHAR(100),
    ADD COLUMN destination_account_id BIGINT;
