-- Document Management schema (governance schemaName: documents_schema).
-- Rollback: DROP TABLE signature_ceremonies, documents, document_templates;

CREATE TABLE IF NOT EXISTS document_templates (
    id              UUID PRIMARY KEY,
    code            VARCHAR(64) NOT NULL,
    version         VARCHAR(32) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    engine          VARCHAR(32) NOT NULL DEFAULT 'HANDLEBARS',
    body_html       TEXT NOT NULL,
    locale          VARCHAR(16) NOT NULL DEFAULT 'en',
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    product_ref     VARCHAR(64),
    classification  VARCHAR(32) NOT NULL DEFAULT 'restricted',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(128) NOT NULL DEFAULT 'system',
    CONSTRAINT uq_document_templates_code_version UNIQUE (code, version)
);
CREATE INDEX idx_document_templates_code_status ON document_templates(code, status);

CREATE TABLE IF NOT EXISTS documents (
    id                UUID PRIMARY KEY,
    template_code     VARCHAR(64) NOT NULL,
    template_version  VARCHAR(32) NOT NULL,
    sha256            CHAR(64) NOT NULL,
    storage_key       VARCHAR(512) NOT NULL,
    content_type      VARCHAR(128) NOT NULL DEFAULT 'application/pdf',
    size_bytes        BIGINT NOT NULL DEFAULT 0,
    status            VARCHAR(24) NOT NULL DEFAULT 'GENERATED',
    metadata_json     TEXT NOT NULL DEFAULT '{}',
    party_ref         VARCHAR(64),
    case_ref          VARCHAR(64),
    product_ref       VARCHAR(64),
    retain_until      DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_documents_party_ref ON documents(party_ref);
CREATE INDEX idx_documents_template  ON documents(template_code, template_version);

-- Object-store blob backing table lives in V4__add_object_store_blobs.sql (ADR-0161: the shared
-- ObjectStorePort's Postgres adapter, not a service-local reinvention of the same table).

CREATE TABLE IF NOT EXISTS signature_ceremonies (
    id               UUID PRIMARY KEY,
    -- Optimistic-lock column (Hibernate @Version): recordDecision reads then writes across two
    -- separate transactions, so a version check is what turns a concurrent lost-update into a
    -- detectable conflict instead of silent data loss.
    version          INTEGER NOT NULL DEFAULT 0,
    document_id      UUID NOT NULL,
    signers_json     TEXT NOT NULL DEFAULT '[]',
    status           VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    signature_level  VARCHAR(16) NOT NULL DEFAULT 'ADVANCED',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_signature_ceremonies_document_id ON signature_ceremonies(document_id);
