-- ===========================================================================
-- V4__Add_Email_2fa_Notification_Type.sql
-- Purpose: 2FA codes are delivered by email now (TwoFactorEmailListener, replacing
-- TwoFactorSmsListener), so the record each one writes needs a type that says so - EMAIL_2FA on the
-- EMAIL channel, rather than an SMS_2FA row describing a message no phone ever received.
--
-- notification_type_enum is a real PostgreSQL enum (see V1), so a new constant on the Java side is
-- not enough on its own - Hibernate binds the value as that named type and Postgres rejects any
-- label the type does not declare.
--
-- Postgres 12+ allows ALTER TYPE ... ADD VALUE inside a transaction as long as the new label is not
-- also USED in that same transaction, which is why this migration only adds it and nothing else. If
-- a future Flyway version objects anyway, add "-- flyway:executeInTransaction=false" as the first
-- line of this file.
--
-- SMS_2FA stays declared and stays untouched: the rows written before the switch are real history,
-- and V3 already scrubbed the codes out of them. Deleting the label would break every one of them.
-- ===========================================================================

ALTER TYPE notification_type_enum ADD VALUE IF NOT EXISTS 'EMAIL_2FA';
