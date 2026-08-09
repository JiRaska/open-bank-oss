-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

-- ADR-0219 D3 suppression list (#3656 slice 2): the platform do-not-contact store, owned by
-- consent-service as the consent-adjacent state it is — a stop that is NOT a revocation.
-- revoked_at IS NULL marks the active rows the contact-policy gate reads; the partial index
-- covers exactly that read shape. Rollback: DROP TABLE suppressions;
CREATE TABLE suppressions (
    id              UUID                     NOT NULL PRIMARY KEY,
    party_id        UUID                     NOT NULL,
    scope           VARCHAR(10)              NOT NULL,
    value           VARCHAR(255),
    reason_code     VARCHAR(30)              NOT NULL,
    source          VARCHAR(100)             NOT NULL,
    created_by      VARCHAR(255)             NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at      TIMESTAMP WITH TIME ZONE,
    revoked_by      VARCHAR(255),
    CONSTRAINT suppressions_value_shape CHECK (
        (scope = 'ALL' AND value IS NULL) OR (scope IN ('SCOPE', 'TOPIC') AND value IS NOT NULL)
    )
);

CREATE INDEX idx_suppressions_active_party ON suppressions (party_id) WHERE revoked_at IS NULL;
