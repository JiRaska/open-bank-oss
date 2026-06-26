-- ADR-0103 D4: physical removal of the vestigial `channel` column. Already deprecated in
-- V11 with COMMENT and never written by any payment path since D2. Zero reads confirmed.
ALTER TABLE transactions DROP COLUMN channel;
-- Rollback: ALTER TABLE transactions ADD COLUMN channel VARCHAR(50);
-- Data-loss note: none — column contained only NULL or the literal 'API'; no meaningful data.
