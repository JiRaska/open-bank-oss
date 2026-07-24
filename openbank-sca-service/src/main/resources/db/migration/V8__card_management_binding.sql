-- Card-bound SCA dynamic linking: a CARD_MANAGEMENT challenge's device-signed payload binds to a
-- specific card + action (LIMIT_INCREASE, REVEAL_DETAILS, ISSUE, CANCEL, ...) rather than to an
-- amount/payee (payment) or a document hash/ceremony (ADR-0169 D2). Both columns are nullable —
-- every pre-existing purpose (payment/document/login/consent/...) leaves them null, so this is
-- additive and needs no backfill.
-- dynamic_card_action stays VARCHAR (not an enum/CHECK): sca-service does not own the card
-- domain's action vocabulary, and a CHECK would make every new card action a lock-step migration.
-- Rollback: DROP COLUMN dynamic_card_id; DROP COLUMN dynamic_card_action; (no data loss for rows
-- written before this migration — the columns are additive and nullable).
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS dynamic_card_id VARCHAR(255);
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS dynamic_card_action VARCHAR(64);
