-- ===========================================================================
-- V3__Redact_Stored_2fa_Codes.sql
-- Purpose: scrub the one-time 2FA codes that TwoFactorSmsListener used to write into
-- notification_records verbatim ("Your verification code is 663716. It expires in 5 minutes.").
--
-- Those rows are served by GET /api/v1/notifications and rendered on the frontend's notifications
-- page, so a login credential was readable there long after the login it belonged to. The codes
-- expire after five minutes, which made this a narrow window rather than a standing key - but an
-- audit trail should record that a code was sent, not what the code was, and the listener now
-- stores a masked line instead.
--
-- The phone number cannot be recovered from these historical rows (it was never in the message), so
-- the replacement is necessarily generic rather than the "***4567" form new records use.
--
-- Scoped to SMS_2FA rows only: transaction alerts, daily summaries and profile-security notices
-- carry no credential and are left exactly as they were dispatched.
-- ===========================================================================

UPDATE notification_records
SET message = 'Verification code sent to the phone number on file.'
WHERE type = 'SMS_2FA'
  AND message LIKE 'Your verification code is%';
