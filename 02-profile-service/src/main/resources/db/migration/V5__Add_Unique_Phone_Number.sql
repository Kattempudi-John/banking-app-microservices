-- ===========================================================================
-- V5__Add_Unique_Phone_Number.sql
-- Purpose: stop two profiles from holding the same phone number.
--
-- auth-service is the owner of this field and is where the uniqueness rule is actually enforced
-- (ProfileManagementService.updateContactInfo writes through to it before saving anything here).
-- This index is the mirror-side backstop for that: if a future code path ever writes this column
-- without going through auth-service - a Kafka consumer, a backfill, a hand-run UPDATE - the
-- database refuses it instead of quietly recreating the duplicate this migration exists to end.
--
-- A unique index rather than a UNIQUE constraint because Postgres allows unlimited NULLs under one,
-- which is required here: every profile provisioned from a "user-events" message that carried no
-- phone number has NULL, and those are not duplicates of each other. Existing rows have already
-- been de-duplicated, so this applies cleanly.
-- ===========================================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_profiles_phone_number
    ON user_profiles (phone_number);
