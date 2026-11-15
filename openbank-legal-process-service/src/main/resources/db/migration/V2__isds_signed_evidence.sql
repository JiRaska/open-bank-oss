-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Rollback: stop the ISDS importer and retain the append-only evidence.
CREATE TABLE legal_isds_evidence (
    evidence_id UUID PRIMARY KEY,
    signed_message BYTEA NOT NULL CHECK (octet_length(signed_message) BETWEEN 1 AND 26214400),
    signed_delivery_info BYTEA NOT NULL CHECK (octet_length(signed_delivery_info) BETWEEN 1 AND 26214400)
);
