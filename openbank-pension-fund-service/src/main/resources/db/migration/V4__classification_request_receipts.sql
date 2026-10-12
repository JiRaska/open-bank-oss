-- Durable, actor/operation-scoped replay snapshots commit with the correction mutation.
-- Keys and request fingerprints are SHA-256 digests; snapshots retain the original result,
-- including a PROPOSED response after the correction has subsequently been decided.
-- Rollback: DROP TABLE classification_request_receipts;
-- Rolling back loses replay protection for previously acknowledged correction requests.
CREATE TABLE classification_request_receipts (
    receipt_key VARCHAR(64) PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    response_snapshot TEXT NOT NULL
);
