-- ===========================================================================
-- V2__Add_Daily_Summary_Notification_Type.sql
-- Purpose: DailyBalanceSummaryJob now writes a NotificationRecord like the three Kafka listeners
-- already did, so the summary it mails out shows up in GET /api/v1/notifications instead of being
-- the one notification type with no trace beyond the log.
--
-- notification_type_enum is a real PostgreSQL enum (see V1), so a new constant on the Java side is
-- not enough on its own - Hibernate binds the value as that named type and Postgres rejects any
-- label the type does not declare.
--
-- Postgres 12+ allows ALTER TYPE ... ADD VALUE inside a transaction as long as the new label is not
-- also USED in that same transaction, which is why this migration only adds it and nothing else. If
-- a future Flyway version objects anyway, add "-- flyway:executeInTransaction=false" as the first
-- line of this file.
-- ===========================================================================

ALTER TYPE notification_type_enum ADD VALUE IF NOT EXISTS 'DAILY_SUMMARY';
