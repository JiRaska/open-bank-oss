-- ADR-0334 / #12379: the participant's annual statement, issued once per contract and year through
-- document-service. The row holds document-service's id and its SHA-256 of the STORED document, so
-- a statement shown later can be proven to be the one issued. Insert-once (PRIMARY KEY + the
-- application's ON CONFLICT DO NOTHING): a retried or concurrent issue never yields a second one.
--
-- Rollback: DROP TABLE pension_annual_statements; (nothing references it; the documents stay in
-- document-service, and re-issuing after a rollback renders a new document for the year).
CREATE TABLE pension_annual_statements (
    contract_id     UUID        NOT NULL REFERENCES pension_contracts (contract_id),
    statement_year  INTEGER     NOT NULL CHECK (statement_year BETWEEN 1994 AND 2200),
    document_id     VARCHAR(64) NOT NULL,
    document_sha256 CHAR(64)    NOT NULL CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
    issued_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (contract_id, statement_year)
);
