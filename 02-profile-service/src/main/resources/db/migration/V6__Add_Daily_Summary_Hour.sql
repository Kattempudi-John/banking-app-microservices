-- ===========================================================================
-- V6__Add_Daily_Summary_Hour.sql
-- Purpose: let each user pick the hour their daily balance summary is delivered.
--
-- The hour used to be notification.daily-summary.hour in notification-service - one global config
-- value applied to every customer at once, so "8am" was 8am for everybody or for nobody. Timezone
-- and the on/off toggle were already per-user on this table (V3), so the hour was the last piece
-- of that schedule still living outside the user's own preferences.
--
-- V6, not V7 or V8: this service has its own Flyway history table (spring.flyway.table =
-- flyway_schema_history_profile, see application.yml), so its version numbers are independent of
-- account-service and transaction-service, which share the default table and are already past this
-- number with entirely different content.
--
-- NOT NULL DEFAULT 8 rather than a nullable column: 8 is exactly the value the old global config
-- shipped, so every existing row keeps sending at the same hour it does today and nothing has to
-- interpret NULL as "whatever the default happens to be" at read time. The 0..23 range is enforced
-- in the API (@Min/@Max on UpdateDailySummaryRequestDto) rather than as a CHECK constraint here,
-- so an out-of-range hour comes back as a readable 400 instead of a constraint-violation 500.
-- ===========================================================================

ALTER TABLE user_preferences
ADD COLUMN daily_summary_hour INTEGER NOT NULL DEFAULT 8;
