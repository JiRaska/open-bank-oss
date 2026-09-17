-- ADR-0284 D3 / #10247: one signed KYB case projects every mandate, its outbox events and
-- optional statutory policy in ONE party-service transaction. The case marker is the idempotency
-- key and is inserted in that same transaction. No historical mandate is rewritten by migration.
-- Rollback: stop the event projector and retain this table as reconciliation evidence. Drop only
-- after separately approved archival; image rollback must not erase projected authority history.

CREATE TABLE party_kyb_signed_case_projections (
    case_id        UUID PRIMARY KEY,
    payload_hash   CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    mandate_count  INTEGER NOT NULL CHECK (mandate_count > 0),
    projected_at   TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION refuse_kyb_signed_case_projection_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'signed KYB case projection markers are append-only' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_party_kyb_signed_case_projection_immutable
    BEFORE UPDATE OR DELETE ON party_kyb_signed_case_projections
    FOR EACH ROW EXECUTE FUNCTION refuse_kyb_signed_case_projection_mutation();
