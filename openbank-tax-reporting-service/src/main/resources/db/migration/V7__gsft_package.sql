-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- #15356: the GSFT depositor batch as assembled — the deterministic ZIP over the decree files,
-- keyed by its SHA-256. The cz-gsft statutory return attests that hash (and each file's) in its
-- lines; the delivery route reloads the archive by it, re-verifies every hash and only then
-- encrypts it for GSFT. Content-addressed, so an identical re-assembly is one row and a row is
-- never updated. It holds depositor personal data: it is read only by the delivery route.
--
-- Rollback: DROP TABLE gsft_package; (only once no cz-gsft return awaits delivery — an APPROVED
--           return whose archive is gone can no longer be submitted).
CREATE TABLE gsft_package (
    archive_sha256  VARCHAR(64)  PRIMARY KEY,
    archive_name    VARCHAR(64)  NOT NULL,
    archive         BYTEA        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_gsft_package_sha256 CHECK (archive_sha256 ~ '^[0-9a-f]{64}$')
);
