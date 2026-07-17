-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- #1201: atomic FOR UPDATE SKIP LOCKED row-claim so two concurrently running dispatcher
-- instances (e.g. both pods live during an Argo Rollouts canary window) cannot select and
-- publish the same PENDING/FAILED rows. claimed_at marks when a row moved to DISPATCHING, so a
-- stale claim (the claiming pod crashed or was evicted before markSent/markFailed) can be
-- reclaimed on a later tick instead of stranding the row forever.
ALTER TABLE sdd_outbox ADD COLUMN claimed_at TIMESTAMPTZ;
