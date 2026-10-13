-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- A v1 return's existing content_hash bytes and approval remain unchanged. New participant-sourced
-- revisions store the exact immutable close receipt and cell paths attested by hash version 2.
-- Rollback: before any v2 return exists, DROP CONSTRAINT ck_statutory_return_lineage_version,
-- DROP INDEX uq_statutory_return_source_assembly_key, then DROP the four columns added below.
-- Never roll back after a v2 return is assembled: that would destroy its evidentiary source links
-- and successful command receipt. Preserve the database and roll forward instead.
ALTER TABLE statutory_return
    ADD COLUMN content_hash_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN participant_close_lineage_json TEXT,
    ADD COLUMN source_assembly_key_hash CHAR(64),
    ADD COLUMN source_assembly_request_hash CHAR(64);

CREATE UNIQUE INDEX uq_statutory_return_source_assembly_key
    ON statutory_return (source_assembly_key_hash)
    WHERE source_assembly_key_hash IS NOT NULL;

ALTER TABLE statutory_return
    ADD CONSTRAINT ck_statutory_return_lineage_version CHECK (
        (content_hash_version = 1 AND participant_close_lineage_json IS NULL AND
            source_assembly_key_hash IS NULL AND source_assembly_request_hash IS NULL) OR
        (content_hash_version = 2 AND participant_close_lineage_json IS NOT NULL AND
            source_assembly_key_hash IS NOT NULL AND source_assembly_request_hash IS NOT NULL)
    );
