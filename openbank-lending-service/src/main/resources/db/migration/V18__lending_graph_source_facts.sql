-- SPDX-License-Identifier: Apache-2.0
-- ADR-0307 expand stage. These source facts are separate from collateral/IFRS 9 and
-- do not change a loan, a provision, a posting, or an existing collateral row.
-- No historical collateral is auto-matched: identity and guarantee evidence must
-- be explicitly proposed and approved before a Context edge can be published.
-- Rollback before first writer: DROP TABLE lending_graph_guarantee;
-- DROP TABLE lending_graph_valuation; DROP TABLE lending_graph_allocation;
-- DROP TABLE lending_graph_asset. After adoption, stop writers/readers and retain
-- evidence under the bank's retention policy; dropping populated facts is not rollback.

CREATE TABLE lending_graph_asset (
    asset_id UUID PRIMARY KEY,
    asset_type VARCHAR(32) NOT NULL CHECK (asset_type IN
        ('REAL_ESTATE', 'VEHICLE', 'SECURITIES', 'CASH_DEPOSIT', 'OTHER')),
    source_document_id UUID NOT NULL,
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    supersedes_asset_id UUID UNIQUE REFERENCES lending_graph_asset(asset_id),
    proposed_by VARCHAR(128) NOT NULL CHECK (length(trim(proposed_by)) > 0),
    proposed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by VARCHAR(128),
    decided_at TIMESTAMPTZ,
    CONSTRAINT lending_graph_asset_no_self_correction CHECK
        (supersedes_asset_id IS NULL OR supersedes_asset_id <> asset_id),
    CONSTRAINT lending_graph_asset_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL) OR
        (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by <> proposed_by AND decided_at >= proposed_at)
    )
);

CREATE TABLE lending_graph_allocation (
    allocation_id UUID PRIMARY KEY,
    asset_id UUID NOT NULL REFERENCES lending_graph_asset(asset_id),
    collateral_id UUID NOT NULL REFERENCES collateral(id),
    revision BIGINT NOT NULL CHECK (revision > 0),
    supersedes_allocation_id UUID UNIQUE REFERENCES lending_graph_allocation(allocation_id),
    secured_amount NUMERIC(20,2) NOT NULL CHECK (secured_amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    priority INTEGER NOT NULL CHECK (priority > 0),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    source_document_id UUID NOT NULL,
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    proposed_by VARCHAR(128) NOT NULL CHECK (length(trim(proposed_by)) > 0),
    proposed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by VARCHAR(128),
    decided_at TIMESTAMPTZ,
    CONSTRAINT lending_graph_allocation_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT lending_graph_allocation_revision UNIQUE (collateral_id, revision),
    CONSTRAINT lending_graph_allocation_lineage CHECK (
        (revision = 1 AND supersedes_allocation_id IS NULL) OR
        (revision > 1 AND supersedes_allocation_id IS NOT NULL)
    ),
    CONSTRAINT lending_graph_allocation_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL) OR
        (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by <> proposed_by AND decided_at >= proposed_at)
    )
);
CREATE INDEX idx_lending_graph_allocation_asset ON lending_graph_allocation(asset_id, valid_from DESC);

-- An allocation proposal is permitted only for an approved asset identity and
-- an approved, unreleased collateral row of the same type and currency. This
-- prevents a pending or mismatched legacy row from becoming a shared-asset edge.
CREATE FUNCTION guard_lending_graph_allocation_source() RETURNS trigger AS $$
DECLARE
    asset_kind VARCHAR(32);
    prior_collateral_id UUID;
    prior_revision BIGINT;
    prior_status VARCHAR(16);
    legacy_type VARCHAR(32);
    legacy_status VARCHAR(16);
    legacy_currency CHAR(3);
    legacy_released_at TIMESTAMPTZ;
BEGIN
    SELECT asset_type INTO asset_kind FROM lending_graph_asset
        WHERE asset_id = NEW.asset_id AND status = 'APPROVED';
    IF asset_kind IS NULL THEN
        RAISE EXCEPTION 'approved asset identity is required';
    END IF;
    SELECT type::text, status::text, currency, released_at
      INTO legacy_type, legacy_status, legacy_currency, legacy_released_at
      FROM collateral WHERE id = NEW.collateral_id;
    IF legacy_status IS DISTINCT FROM 'APPROVED' OR legacy_released_at IS NOT NULL OR
       legacy_type IS DISTINCT FROM asset_kind OR legacy_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'approved matching collateral evidence is required';
    END IF;
    IF NEW.supersedes_allocation_id IS NOT NULL THEN
        SELECT collateral_id, revision, status
          INTO prior_collateral_id, prior_revision, prior_status
          FROM lending_graph_allocation WHERE allocation_id = NEW.supersedes_allocation_id;
        IF prior_collateral_id IS DISTINCT FROM NEW.collateral_id OR
           prior_revision IS DISTINCT FROM NEW.revision - 1 OR prior_status IS DISTINCT FROM 'APPROVED' THEN
            RAISE EXCEPTION 'allocation correction must supersede the approved prior revision';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER lending_graph_allocation_source_guard
    BEFORE INSERT ON lending_graph_allocation
    FOR EACH ROW EXECUTE FUNCTION guard_lending_graph_allocation_source();

CREATE TABLE lending_graph_valuation (
    valuation_id UUID PRIMARY KEY,
    asset_id UUID NOT NULL REFERENCES lending_graph_asset(asset_id),
    amount NUMERIC(20,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    basis VARCHAR(32) NOT NULL CHECK (basis IN ('MARKET', 'INDEPENDENT_APPRAISAL', 'REGISTERED_VALUE')),
    effective_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    source_document_id UUID NOT NULL,
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    supersedes_valuation_id UUID UNIQUE REFERENCES lending_graph_valuation(valuation_id),
    proposed_by VARCHAR(128) NOT NULL CHECK (length(trim(proposed_by)) > 0),
    proposed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by VARCHAR(128),
    decided_at TIMESTAMPTZ,
    CONSTRAINT lending_graph_valuation_interval CHECK (expires_at IS NULL OR expires_at > effective_at),
    CONSTRAINT lending_graph_valuation_no_self_correction CHECK
        (supersedes_valuation_id IS NULL OR supersedes_valuation_id <> valuation_id),
    CONSTRAINT lending_graph_valuation_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL) OR
        (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by <> proposed_by AND decided_at >= proposed_at)
    )
);
CREATE INDEX idx_lending_graph_valuation_asset ON lending_graph_valuation(asset_id, effective_at DESC);

CREATE FUNCTION guard_lending_graph_asset_lineage() RETURNS trigger AS $$
DECLARE
    prior_asset_id UUID;
    prior_status VARCHAR(16);
BEGIN
    IF NEW.supersedes_asset_id IS NOT NULL THEN
        SELECT asset_id, status INTO prior_asset_id, prior_status
          FROM lending_graph_asset WHERE asset_id = NEW.supersedes_asset_id;
        IF prior_asset_id IS NULL OR prior_status IS DISTINCT FROM 'APPROVED' THEN
            RAISE EXCEPTION 'asset correction must supersede an approved identity';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION guard_lending_graph_valuation_lineage() RETURNS trigger AS $$
DECLARE
    prior_asset_id UUID;
    prior_status VARCHAR(16);
BEGIN
    IF NEW.supersedes_valuation_id IS NOT NULL THEN
        SELECT asset_id, status INTO prior_asset_id, prior_status
          FROM lending_graph_valuation WHERE valuation_id = NEW.supersedes_valuation_id;
        IF prior_asset_id IS DISTINCT FROM NEW.asset_id OR prior_status IS DISTINCT FROM 'APPROVED' THEN
            RAISE EXCEPTION 'valuation correction must supersede an approved value for the same asset';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER lending_graph_asset_lineage_guard
    BEFORE INSERT ON lending_graph_asset
    FOR EACH ROW EXECUTE FUNCTION guard_lending_graph_asset_lineage();
CREATE TRIGGER lending_graph_valuation_lineage_guard
    BEFORE INSERT ON lending_graph_valuation
    FOR EACH ROW EXECUTE FUNCTION guard_lending_graph_valuation_lineage();

CREATE TABLE lending_graph_guarantee (
    guarantee_id UUID PRIMARY KEY,
    contract_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    supersedes_guarantee_id UUID UNIQUE REFERENCES lending_graph_guarantee(guarantee_id),
    loan_id UUID NOT NULL REFERENCES loan(id),
    guarantor_party_id UUID NOT NULL,
    cap_amount NUMERIC(20,2) NOT NULL CHECK (cap_amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    coverage_fraction NUMERIC(7,6) NOT NULL CHECK (coverage_fraction > 0 AND coverage_fraction <= 1),
    seniority INTEGER NOT NULL CHECK (seniority > 0),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    source_document_id UUID NOT NULL,
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    proposed_by VARCHAR(128) NOT NULL CHECK (length(trim(proposed_by)) > 0),
    proposed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by VARCHAR(128),
    decided_at TIMESTAMPTZ,
    CONSTRAINT lending_graph_guarantee_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT lending_graph_guarantee_revision UNIQUE (contract_id, revision),
    CONSTRAINT lending_graph_guarantee_lineage CHECK (
        (revision = 1 AND supersedes_guarantee_id IS NULL) OR
        (revision > 1 AND supersedes_guarantee_id IS NOT NULL)
    ),
    CONSTRAINT lending_graph_guarantee_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL) OR
        (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by <> proposed_by AND decided_at >= proposed_at)
    )
);
CREATE INDEX idx_lending_graph_guarantee_loan ON lending_graph_guarantee(loan_id, valid_from DESC);
CREATE INDEX idx_lending_graph_guarantee_party ON lending_graph_guarantee(guarantor_party_id, valid_from DESC);

CREATE FUNCTION guard_lending_graph_guarantee_lineage() RETURNS trigger AS $$
DECLARE
    prior_contract_id UUID;
    prior_revision BIGINT;
    prior_status VARCHAR(16);
BEGIN
    IF NEW.supersedes_guarantee_id IS NOT NULL THEN
        SELECT contract_id, revision, status
          INTO prior_contract_id, prior_revision, prior_status
          FROM lending_graph_guarantee WHERE guarantee_id = NEW.supersedes_guarantee_id;
        IF prior_contract_id IS DISTINCT FROM NEW.contract_id OR
           prior_revision IS DISTINCT FROM NEW.revision - 1 OR prior_status IS DISTINCT FROM 'APPROVED' THEN
            RAISE EXCEPTION 'guarantee correction must supersede the approved prior revision';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER lending_graph_guarantee_lineage_guard
    BEFORE INSERT ON lending_graph_guarantee
    FOR EACH ROW EXECUTE FUNCTION guard_lending_graph_guarantee_lineage();

-- Facts are immutable. The sole mutation is the different-person decision of a
-- pending proposal; approved/rejected rows cannot be rewritten or deleted.
CREATE FUNCTION guard_lending_graph_fact_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'lending graph evidence cannot be truncated';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'lending graph evidence cannot be deleted';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status = 'PENDING' OR
       (to_jsonb(NEW) - 'status' - 'decided_by' - 'decided_at') <>
       (to_jsonb(OLD) - 'status' - 'decided_by' - 'decided_at') THEN
        RAISE EXCEPTION 'lending graph evidence is immutable except for a pending decision';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER lending_graph_asset_immutable
    BEFORE UPDATE OR DELETE ON lending_graph_asset
    FOR EACH ROW EXECUTE FUNCTION guard_lending_graph_fact_mutation();
CREATE TRIGGER lending_graph_allocation_immutable
    BEFORE UPDATE OR DELETE ON lending_graph_allocation
    FOR EACH ROW EXECUTE FUNCTION guard_lending_graph_fact_mutation();
CREATE TRIGGER lending_graph_valuation_immutable
    BEFORE UPDATE OR DELETE ON lending_graph_valuation
    FOR EACH ROW EXECUTE FUNCTION guard_lending_graph_fact_mutation();
CREATE TRIGGER lending_graph_guarantee_immutable
    BEFORE UPDATE OR DELETE ON lending_graph_guarantee
    FOR EACH ROW EXECUTE FUNCTION guard_lending_graph_fact_mutation();
CREATE TRIGGER lending_graph_asset_no_truncate
    BEFORE TRUNCATE ON lending_graph_asset
    FOR EACH STATEMENT EXECUTE FUNCTION guard_lending_graph_fact_mutation();
CREATE TRIGGER lending_graph_allocation_no_truncate
    BEFORE TRUNCATE ON lending_graph_allocation
    FOR EACH STATEMENT EXECUTE FUNCTION guard_lending_graph_fact_mutation();
CREATE TRIGGER lending_graph_valuation_no_truncate
    BEFORE TRUNCATE ON lending_graph_valuation
    FOR EACH STATEMENT EXECUTE FUNCTION guard_lending_graph_fact_mutation();
CREATE TRIGGER lending_graph_guarantee_no_truncate
    BEFORE TRUNCATE ON lending_graph_guarantee
    FOR EACH STATEMENT EXECUTE FUNCTION guard_lending_graph_fact_mutation();

-- Grants match the existing Lending migration convention. Reads and writes are
-- still unreachable until a separately reviewed source adapter is enabled.
GRANT SELECT, INSERT, UPDATE ON lending_graph_asset TO openbank;
GRANT SELECT, INSERT, UPDATE ON lending_graph_allocation TO openbank;
GRANT SELECT, INSERT, UPDATE ON lending_graph_valuation TO openbank;
GRANT SELECT, INSERT, UPDATE ON lending_graph_guarantee TO openbank;
