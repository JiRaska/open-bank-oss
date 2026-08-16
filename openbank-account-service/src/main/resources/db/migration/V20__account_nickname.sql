-- Customer-chosen display label for an account (rename feature).
-- Rollback:
--   ALTER TABLE accounts DROP COLUMN nickname;

ALTER TABLE accounts ADD COLUMN nickname VARCHAR(60);
