-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0336: catalogue-defined statutory returns (pension ČNB PSP/PEF first) on the ADR-0180
-- filing lifecycle. One row per REVISION: a correction is a new revision, never an UPDATE of the
-- figures, so what was attested and submitted stays reproducible.
--
-- Rollback: DROP TABLE statutory_return; (no other table references it).
CREATE TABLE statutory_return (
    id                   UUID PRIMARY KEY,
    catalogue_id         VARCHAR(64)  NOT NULL,
    catalogue_version    INTEGER      NOT NULL,
    return_code          VARCHAR(64)  NOT NULL,
    entity_id            VARCHAR(128) NOT NULL,
    periodicity          VARCHAR(16)  NOT NULL,
    period_end           DATE         NOT NULL,
    revision             INTEGER      NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    datapoints_json      TEXT         NOT NULL,
    content_hash         CHAR(64)     NOT NULL,
    due_date             DATE         NOT NULL,
    assembled_by         VARCHAR(255) NOT NULL,
    assembled_at         TIMESTAMPTZ  NOT NULL,
    approved_by          VARCHAR(255),
    approved_at          TIMESTAMPTZ,
    attested_hash        CHAR(64),
    submitted_by         VARCHAR(255),
    submitted_at         TIMESTAMPTZ,
    submission_reference VARCHAR(255),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_statutory_return_revision UNIQUE (catalogue_id, return_code, entity_id, periodicity, period_end, revision),
    CONSTRAINT ck_statutory_return_four_eyes CHECK (approved_by IS NULL OR approved_by <> assembled_by)
);

CREATE INDEX ix_statutory_return_due ON statutory_return (status, due_date);
