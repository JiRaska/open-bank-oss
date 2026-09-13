-- Saved payees (TOP-10 #5): server-synced version of the mobile app's device-local payee list.
-- Rollback:
--   DROP TABLE party_payees;
--   DROP SEQUENCE "party_payees_SEQ";

CREATE TABLE party_payees (
    id         BIGSERIAL PRIMARY KEY,
    payee_id   UUID NOT NULL UNIQUE,
    party_id   UUID NOT NULL,
    name       VARCHAR(120) NOT NULL,
    iban       VARCHAR(34) NOT NULL,
    bic        VARCHAR(11),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_party_payees_party_id ON party_payees (party_id);

-- Dedup key: a re-save of the same IBAN updates the existing row (see PartyPayeeRepositoryImpl.save),
-- matching the app's own "re-add moves it to the front" rule instead of accumulating duplicate rows.
ALTER TABLE party_payees ADD CONSTRAINT uq_party_payees_party_iban UNIQUE (party_id, iban);

-- Panache's default SEQUENCE id-generation strategy for the entity's surrogate `id`.
CREATE SEQUENCE IF NOT EXISTS "party_payees_SEQ" INCREMENT BY 50;
