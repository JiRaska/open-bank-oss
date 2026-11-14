-- Verified statutory rules are immutable and ordered by the time of the source attestation.
-- Rollback: DROP TABLE party_statutory_representation_rules;
CREATE TABLE party_statutory_representation_rules (
    attestation_id UUID PRIMARY KEY,
    principal_party_id UUID NOT NULL,
    attested_at TIMESTAMPTZ NOT NULL,
    snapshot TEXT NOT NULL,
    CONSTRAINT uq_party_statutory_rule_attestation_time UNIQUE (principal_party_id, attested_at)
);
