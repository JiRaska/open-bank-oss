-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- #15356: GSFT depositor-data delivery.
--
-- gsft_package    the assembled batch as one deterministic ZIP, keyed by its SHA-256. The cz-gsft
--                 statutory return attests that hash (and each file's) in its lines; it is written
--                 only AFTER the return row has validated and been stored, and never updated.
-- gsft_delivery   the PGP-encrypted archive handed over for a return, written BEFORE the return
--                 moves to SUBMITTED, so a submitted return always has the artefact that left, and
--                 it can be fetched again until GSFT answers.
-- gsft_batch_counter  the batch number (annex 1.1.1, 00001..99999) per bank code, shared by TEST and
--                 LIVE and advanced atomically. Whether GSFT expects this exact sequence is
--                 UNVERIFIED (docs/gsft-depositor-data.md).
--
-- PERSONAL DATA AT REST — ACCEPTED CONTROL AND OPEN GAP. gsft_package.archive holds depositor names,
-- birth numbers and addresses in CLEARTEXT bytea. This service has no envelope-encryption (transit)
-- protector, so the accepted control is the database's own: the tax-reporting database is encrypted
-- at rest by its storage and backups, only this service's role can read it, and only the delivery
-- route reads the column. gsft_delivery.encrypted is ciphertext for GSFT. Retention rule: both rows
-- of a return are to be deleted once that return is ACKNOWLEDGED or REJECTED and no correction
-- revision is open; NO purge job exists yet — that, and column-level envelope encryption, are open
-- gaps on #15356.
--
-- Rollback: DROP TABLE gsft_delivery; DROP TABLE gsft_package; DROP TABLE gsft_batch_counter;
--           (only once no cz-gsft return is APPROVED or SUBMITTED — such a return can then no
--           longer be delivered or re-fetched).
CREATE TABLE gsft_package (
    archive_sha256  VARCHAR(64)  PRIMARY KEY,
    archive_name    VARCHAR(64)  NOT NULL,
    archive         BYTEA        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_gsft_package_sha256 CHECK (archive_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE gsft_delivery (
    return_id   UUID          PRIMARY KEY REFERENCES statutory_return (id),
    file_name   VARCHAR(80)   NOT NULL,
    encrypted   BYTEA         NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL
);

CREATE TABLE gsft_batch_counter (
    bank_code    VARCHAR(4)  PRIMARY KEY,
    last_number  INTEGER     NOT NULL,
    CONSTRAINT ck_gsft_batch_counter_range CHECK (last_number BETWEEN 1 AND 99999)
);
