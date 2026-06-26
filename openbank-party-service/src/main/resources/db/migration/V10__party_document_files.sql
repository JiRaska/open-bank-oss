-- V9: Binary document files for mobile KYC upload (Sprint 1).
-- Sprint 1: stored as bytea. Production: replace with S3 object references.
-- Rollback: DROP TABLE party_document_files;
CREATE TABLE party_document_files (
    id                  UUID        NOT NULL PRIMARY KEY,
    party_id            UUID        NOT NULL,
    document_type       VARCHAR(50) NOT NULL,
    file_name           VARCHAR(255),
    mime_type           VARCHAR(100) NOT NULL,
    content             BYTEA       NOT NULL,
    uploaded_at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_document_files_party_id ON party_document_files(party_id);
