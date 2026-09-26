-- AML questionnaire, customer declarations and the signed framework agreement on the business
-- onboarding case (AML Act 253/2008 §9, FATCA/CRS; the free-text signatureRef is replaced by a
-- document-service SCA ceremony that the case records here).
--
-- Additive and nullable: every existing case reads back with all three absent, which the
-- aggregate treats as "not answered yet".
--
-- Rollback: ALTER TABLE kyb_cases DROP COLUMN questionnaire_json, DROP COLUMN declarations_json,
--           DROP COLUMN agreement_json;
-- Dropping loses the recorded answers and the ceremony binding of any case that used them, so
-- roll the application back first (the previous release never reads these columns).
ALTER TABLE kyb_cases ADD COLUMN questionnaire_json TEXT;
ALTER TABLE kyb_cases ADD COLUMN declarations_json TEXT;
ALTER TABLE kyb_cases ADD COLUMN agreement_json TEXT;
