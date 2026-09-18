-- SPDX-License-Identifier: Apache-2.0
-- Preserve the version read before a lifecycle write, including the compare-and-consume gate.
-- Existing rows start at zero; no status, signature or consumption marker is rewritten.
ALTER TABLE sca_challenges ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

-- Rollback: retain this additive column when rolling back the binary. Old writers do not
-- participate in version checks, so drain all old instances before enabling the new guarantee.
-- Reverting to old lifecycle writers reintroduces stale-write and replay risks; it is not a
-- safe recovery while challenges remain actionable. Drop the column only after all new
-- binaries are removed and outstanding challenges have been expired/reconciled.
