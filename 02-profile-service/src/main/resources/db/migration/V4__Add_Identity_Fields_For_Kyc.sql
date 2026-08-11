-- ===========================================================================
-- V4__Add_Identity_Fields_For_Kyc.sql
-- Purpose: capture the identity half of KYC. Until now the profile held contact details only
-- (phone + address), which is not something an identity check can actually be run against - a real
-- vendor verifies a legal name and date of birth against that address. These two columns are what
-- the Profile page's verification form now collects, and completing them is what moves a user from
-- PENDING_VERIFICATION to APPROVED (see ProfileManagementService.updateContactInfo).
--
-- Both nullable on purpose: every profile created before this migration has neither, and backfilling
-- an invented name or birth date would be worse than leaving them empty. Those users simply stay
-- PENDING_VERIFICATION until they fill the form in, which is the correct state for someone whose
-- identity genuinely has not been verified.
-- ===========================================================================

ALTER TABLE user_profiles
ADD COLUMN legal_name VARCHAR(255);

ALTER TABLE user_profiles
ADD COLUMN date_of_birth DATE;
