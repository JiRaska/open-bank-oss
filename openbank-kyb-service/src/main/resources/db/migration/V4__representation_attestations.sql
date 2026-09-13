-- Human confirmation of how an entity is represented (#9711).
--
-- Keyed by identifier AND the hash of the register's rule text: a company that amends its
-- způsob jednání keeps its IČO, so a key on the identifier alone would carry yesterday's
-- confirmation onto today's different rule — the exact failure this control exists to prevent.
--
-- Rollback: DROP TABLE kyb_representation_attestations; DROP SEQUENCE kyb_representation_attestations_seq;
-- Safe to drop — no other table references it, and cases carry their own resolved
-- required_signatures / required_signer_roles once verified.
CREATE TABLE kyb_representation_attestations (
    -- BIGINT, not BIGSERIAL: PanacheEntity always supplies the id from the sequence below, so a
    -- column default would be a SECOND id source starting at 1 alongside it. Anything inserting
    -- raw SQL (a fixture, a backfill) would take id 1 from the default and then collide with the
    -- app's first insert on the primary key — measured, not hypothetical.
    id                 BIGINT PRIMARY KEY,
    attestation_id     UUID        NOT NULL UNIQUE,
    identifier_scheme  VARCHAR(16) NOT NULL,
    identifier_value   VARCHAR(64) NOT NULL,
    rule_text_hash     CHAR(64)    NOT NULL,
    rule_text          TEXT,
    parsed_mode        VARCHAR(16) NOT NULL,
    parsed_signers     INT,
    confirmed_signers  INT         NOT NULL CHECK (confirmed_signers >= 1),
    confirmed_roles    TEXT        NOT NULL DEFAULT '[]',
    attested_by        VARCHAR(128) NOT NULL,
    attested_at        TIMESTAMPTZ NOT NULL,
    superseded_at      TIMESTAMPTZ,
    note               TEXT
);

-- At most ONE active attestation per (entity, rule text). A partial unique index rather than a
-- plain one: superseded rows are kept for the audit trail and must be free to repeat the key.
CREATE UNIQUE INDEX uq_kyb_repr_attest_active
    ON kyb_representation_attestations (identifier_scheme, identifier_value, rule_text_hash)
    WHERE superseded_at IS NULL;

CREATE INDEX idx_kyb_repr_attest_entity
    ON kyb_representation_attestations (identifier_scheme, identifier_value, attested_at DESC);

-- Hibernate Reactive + PanacheEntity allocate ids from "<table>_seq" (allocationSize 50), as V1
-- does for every other table here. Without it every insert dies on a missing relation — a defect
-- no unit test can see, because a mocked repository issues no SQL.
CREATE SEQUENCE IF NOT EXISTS kyb_representation_attestations_seq INCREMENT BY 50;
GRANT ALL ON SEQUENCE kyb_representation_attestations_seq TO openbank;

-- The offices an attested rule names, when it names them. Empty list = a plain count.
-- Rollback: ALTER TABLE kyb_cases DROP COLUMN required_signer_roles;
ALTER TABLE kyb_cases ADD COLUMN required_signer_roles TEXT NOT NULL DEFAULT '[]';
