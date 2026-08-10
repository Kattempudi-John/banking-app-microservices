-- ===========================================================================
-- V1__Create_Notification_Records_Table.sql
-- Purpose: first-ever table for this service - a durable, queryable log of every notification
-- dispatched (or attempted), backing GET /api/v1/notifications. Previously this service only ever
-- logged what it sent, with nothing durable afterward.
-- Runs in its own "notification" schema (see application.yml's flyway.schemas), not the shared
-- default schema account-service/transaction-service already occupy - avoids the exact
-- migration-numbering collision hit while building the IBAN feature, same isolation approach
-- 05-audit-service already uses.
-- ===========================================================================

CREATE TYPE notification_type_enum AS ENUM (
    'SMS_2FA',
    'TRANSACTION_ALERT',
    'PROFILE_SECURITY'
);

CREATE TYPE notification_channel_enum AS ENUM (
    'SMS',
    'EMAIL'
);

CREATE TYPE notification_status_enum AS ENUM (
    'SENT',
    'FAILED'
);

CREATE TABLE notification_records (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type notification_type_enum NOT NULL,
    channel notification_channel_enum NOT NULL,
    subject VARCHAR(255),
    message TEXT NOT NULL,
    status notification_status_enum NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_records_user_id ON notification_records (user_id);
