-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- P1/P2 / ADR-0257 expand phase. All tables are additive beside legacy `products`; a P0 binary
-- ignores them and remains the rollback path. There is no tenant column (ADR-0152).
-- Rollback: before production v2 writes, drop catalog_outbox, catalog_audit, catalog_approvals,
-- catalog_relationships, catalog_price_components, catalog_revisions, catalog_offerings,
-- catalog_specifications, catalog_schemas (in that order). Never roll back after publication.
-- Then drop functions enforce_catalog_outbox_payload_immutability, prevent_catalog_evidence_mutation,
-- prevent_published_catalog_revision_delete and enforce_catalog_revision_immutability.

CREATE TABLE catalog_schemas (
    key VARCHAR(320) PRIMARY KEY,
    schema_id VARCHAR(255) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    document JSONB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    UNIQUE (schema_id, schema_version)
);

CREATE TABLE catalog_specifications (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    schema_id VARCHAR(255) NOT NULL,
    schema_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (schema_id, schema_version) REFERENCES catalog_schemas (schema_id, schema_version)
);

CREATE TABLE catalog_offerings (
    id UUID PRIMARY KEY,
    specification_id UUID NOT NULL REFERENCES catalog_specifications (id),
    code VARCHAR(64) NOT NULL UNIQUE,
    market JSONB NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE catalog_revisions (
    id UUID PRIMARY KEY,
    offering_id UUID NOT NULL REFERENCES catalog_offerings (id),
    revision_no BIGINT NOT NULL CHECK (revision_no > 0),
    schema_id VARCHAR(255) NOT NULL,
    schema_version INTEGER NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    content JSONB NOT NULL,
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    maker_id VARCHAR(255) NOT NULL,
    checker_id VARCHAR(255),
    reason TEXT,
    content_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (offering_id, revision_no),
    FOREIGN KEY (schema_id, schema_version) REFERENCES catalog_schemas (schema_id, schema_version),
    CHECK (effective_from IS NULL OR effective_to IS NULL OR effective_to > effective_from),
    CHECK (state <> 'PUBLISHED' OR (checker_id IS NOT NULL AND checker_id <> maker_id))
);

CREATE INDEX idx_catalog_revisions_projection
    ON catalog_revisions (offering_id, state, effective_from, effective_to);

CREATE TABLE catalog_price_components (
    id UUID PRIMARY KEY,
    revision_id UUID NOT NULL REFERENCES catalog_revisions (id),
    code VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('AMOUNT', 'RATE')),
    value NUMERIC(38, 18) NOT NULL CHECK (value >= 0),
    currency CHAR(3),
    unit VARCHAR(64) NOT NULL,
    cadence VARCHAR(24) NOT NULL,
    tax_treatment VARCHAR(24) NOT NULL,
    UNIQUE (revision_id, code),
    CHECK (kind <> 'AMOUNT' OR currency IS NOT NULL)
);

CREATE TABLE catalog_relationships (
    id UUID PRIMARY KEY,
    revision_id UUID NOT NULL REFERENCES catalog_revisions (id),
    target_offering_id UUID NOT NULL REFERENCES catalog_offerings (id),
    kind VARCHAR(32) NOT NULL,
    UNIQUE (revision_id, target_offering_id, kind)
);

CREATE TABLE catalog_approvals (
    id UUID PRIMARY KEY,
    revision_id UUID NOT NULL REFERENCES catalog_revisions (id),
    maker_id VARCHAR(255) NOT NULL,
    checker_id VARCHAR(255) NOT NULL,
    reason TEXT NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    CHECK (maker_id <> checker_id)
);

CREATE TABLE catalog_audit (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    action VARCHAR(64) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    details JSONB NOT NULL
);

CREATE INDEX idx_catalog_audit_aggregate ON catalog_audit (aggregate_type, aggregate_id, occurred_at);

CREATE TABLE catalog_outbox (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_catalog_outbox_pending ON catalog_outbox (occurred_at) WHERE published_at IS NULL;

-- Published revisions are evidentiary snapshots. The only permitted later change is closing the
-- effective interval while marking a predecessor SUPERSEDED; content and approval metadata stay fixed.
CREATE FUNCTION enforce_catalog_revision_immutability() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state IN ('PUBLISHED', 'SUPERSEDED') THEN
        IF OLD.state = 'PUBLISHED'
            AND NEW.state = 'SUPERSEDED'
            AND NEW.id = OLD.id
            AND NEW.offering_id = OLD.offering_id
            AND NEW.revision_no = OLD.revision_no
            AND NEW.schema_id = OLD.schema_id
            AND NEW.schema_version = OLD.schema_version
            AND NEW.content = OLD.content
            AND NEW.effective_from IS NOT DISTINCT FROM OLD.effective_from
            AND NEW.effective_to IS NOT NULL
            AND (OLD.effective_to IS NULL OR NEW.effective_to <= OLD.effective_to)
            AND NEW.maker_id = OLD.maker_id
            AND NEW.checker_id IS NOT DISTINCT FROM OLD.checker_id
            AND NEW.reason IS NOT DISTINCT FROM OLD.reason
            AND NEW.content_hash IS NOT DISTINCT FROM OLD.content_hash
            AND NEW.created_at = OLD.created_at
        THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'published catalog revision % is immutable', OLD.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_catalog_revision_immutability
    BEFORE UPDATE ON catalog_revisions
    FOR EACH ROW EXECUTE FUNCTION enforce_catalog_revision_immutability();

CREATE FUNCTION prevent_published_catalog_revision_delete() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state <> 'DRAFT' THEN
        RAISE EXCEPTION 'published catalog revision % cannot be deleted', OLD.id
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_catalog_revision_delete
    BEFORE DELETE ON catalog_revisions
    FOR EACH ROW EXECUTE FUNCTION prevent_published_catalog_revision_delete();

CREATE FUNCTION prevent_catalog_evidence_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'catalog evidence table % is append-only', TG_TABLE_NAME
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_catalog_schema_immutable
    BEFORE UPDATE OR DELETE ON catalog_schemas
    FOR EACH ROW EXECUTE FUNCTION prevent_catalog_evidence_mutation();

CREATE TRIGGER trg_catalog_approval_immutable
    BEFORE UPDATE OR DELETE ON catalog_approvals
    FOR EACH ROW EXECUTE FUNCTION prevent_catalog_evidence_mutation();

CREATE TRIGGER trg_catalog_audit_immutable
    BEFORE UPDATE OR DELETE ON catalog_audit
    FOR EACH ROW EXECUTE FUNCTION prevent_catalog_evidence_mutation();

CREATE FUNCTION enforce_catalog_outbox_payload_immutability() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id <> OLD.id
        OR NEW.aggregate_type <> OLD.aggregate_type
        OR NEW.aggregate_id <> OLD.aggregate_id
        OR NEW.event_type <> OLD.event_type
        OR NEW.schema_version <> OLD.schema_version
        OR NEW.occurred_at <> OLD.occurred_at
        OR NEW.payload <> OLD.payload
    THEN
        RAISE EXCEPTION 'catalog outbox event % payload is immutable', OLD.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_catalog_outbox_payload_immutable
    BEFORE UPDATE ON catalog_outbox
    FOR EACH ROW EXECUTE FUNCTION enforce_catalog_outbox_payload_immutability();
