-- ADR-0249 D1 — additional cardholder ("dodatková karta").
--
-- A card issued to a delegate names the grant that authorised it. NULL means an ordinary card
-- whose holder is the account owner; every pre-existing row is exactly that, which is why the
-- column is nullable rather than backfilled with a sentinel.
--
-- The index is what makes ADR-0249 D2 affordable: on a DelegationRevoked event the service has to
-- find every card issued under that grant and BLOCK it. Without the index that is a full scan of
-- the cards table on a Kafka consumer's hot path.

ALTER TABLE cards ADD COLUMN delegation_grant_id UUID;

CREATE INDEX idx_cards_delegation_grant
    ON cards(delegation_grant_id) WHERE delegation_grant_id IS NOT NULL;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
