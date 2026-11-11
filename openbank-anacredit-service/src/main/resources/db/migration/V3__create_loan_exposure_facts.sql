-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- #9890: retain lending's effective-dated lifecycle facts before report enrichment.
-- Rollback: stop consuming the fact event and retain this history for replay. Drop the table
-- only if no facts have been accepted or the retained event stream has been independently verified.

CREATE TABLE loan_exposure_facts (
    event_id UUID PRIMARY KEY,
    source_reference VARCHAR(160) NOT NULL UNIQUE,
    loan_id UUID NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX idx_loan_exposure_facts_effective ON loan_exposure_facts (loan_id, effective_at);
