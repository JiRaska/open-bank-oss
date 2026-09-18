-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Expand-only cancellation tombstone. A cancellation may arrive before the opened event after
-- a replay or DLQ recovery, so notification rows alone cannot prevent a stale future prompt.
-- Rollback application code first; retain these immutable facts. Only a separately reviewed
-- contraction may drop this table after all producers and consumers no longer use it.
CREATE TABLE joint_proposal_cancellations (
    operation_id UUID PRIMARY KEY,
    principal_party_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    operation_kind VARCHAR(10) NOT NULL CHECK (operation_kind IN ('ISSUE', 'ACCEPT')),
    cancelled_at TIMESTAMPTZ NOT NULL
);

GRANT ALL ON joint_proposal_cancellations TO openbank;
