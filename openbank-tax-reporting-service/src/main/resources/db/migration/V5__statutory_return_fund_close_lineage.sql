-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Preserve every v1/v2 row and its original attested hash. Version 3 binds an explicitly
-- selected immutable fund close and the exact source paths used by the statutory return.
-- Rollback is possible only before a v3 revision exists: restore the V4 constraint and drop
-- fund_close_lineage_json. Once a v3 revision exists, preserve evidence and roll forward.
ALTER TABLE statutory_return ADD COLUMN fund_close_lineage_json TEXT;

ALTER TABLE statutory_return DROP CONSTRAINT ck_statutory_return_lineage_version;
ALTER TABLE statutory_return ADD CONSTRAINT ck_statutory_return_lineage_version CHECK (
    (content_hash_version = 1 AND participant_close_lineage_json IS NULL AND
        fund_close_lineage_json IS NULL AND source_assembly_key_hash IS NULL AND
        source_assembly_request_hash IS NULL) OR
    (content_hash_version = 2 AND participant_close_lineage_json IS NOT NULL AND
        fund_close_lineage_json IS NULL AND source_assembly_key_hash IS NOT NULL AND
        source_assembly_request_hash IS NOT NULL) OR
    (content_hash_version = 3 AND participant_close_lineage_json IS NULL AND
        fund_close_lineage_json IS NOT NULL AND source_assembly_key_hash IS NOT NULL AND
        source_assembly_request_hash IS NOT NULL)
);
