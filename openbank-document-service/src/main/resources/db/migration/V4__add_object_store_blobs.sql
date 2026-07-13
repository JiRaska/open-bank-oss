-- Backing table for the shared com.openbank.libs.storage.PostgresBlobStore adapter (ADR-0161 D2),
-- selected via openbank.objectstore.backend=postgres (also the default when that key is absent).
-- Exact shape as specified by ObjectStoreBlobEntity's KDoc in openbank-libs-runtime — every
-- consuming service owns its own copy of this migration (ADR-0009: no shared cross-service schema).
-- Rollback: DROP TABLE object_store_blobs;

CREATE TABLE IF NOT EXISTS object_store_blobs (
    storage_key   VARCHAR(1024) PRIMARY KEY,
    content       BYTEA         NOT NULL,
    content_type  VARCHAR(255)  NOT NULL,
    metadata      JSONB,
    created_at    TIMESTAMPTZ   NOT NULL
);
