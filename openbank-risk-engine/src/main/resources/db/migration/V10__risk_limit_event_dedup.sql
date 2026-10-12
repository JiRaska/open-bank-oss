-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0313 D9 / ADR-0329 (#11901) — the "emitted exactly once per (run, limit, limit-set version)"
-- record moves out of risk_outbox into its own narrow table.
--
-- Until now risk_outbox.dedup_key UNIQUE was the only thing stopping a replayed run (the EOD
-- scheduler re-evaluating an existing snapshot, or two pods ticking together) from re-emitting its
-- limit events. That coupled a permanent fact ("this event was emitted") to a delivery buffer, so
-- risk_outbox could never purge its SENT rows: deleting one would let the next replay emit the same
-- breach again under a fresh event_id.
--
-- risk_limit_event_dedup is that permanent fact and nothing else. It is deliberately NOT purged:
-- one row per non-OK limit per EOD run (tens a day at most), no personal data, and keeping it
-- forever makes the exactly-once guarantee unconditional rather than "within the replay window".
-- risk_outbox keeps its own dedup_key UNIQUE as a second line; it becomes purgeable once delivered.
--
-- Backfill: every existing outbox row's key. PgRiskOutbox.purgeSent also catches up rows written
-- by a legacy pod after this one-time migration before it deletes any eligible SENT row.
--
-- Rollback note: additive. A binary from before this migration ignores the table and keeps
-- deduplicating on risk_outbox.dedup_key alone, which is correct for every row SENT-retention has
-- not yet purged. Rows purged after this migration took effect are no longer guarded on such a
-- binary. Disable openbank.outbox.retention.enabled before rollback; that prevents further purges
-- but cannot restore rows already purged. Do not replay those runs on an older binary, or they
-- could re-emit. Never drop this table while a binary that reads it is running.

CREATE TABLE risk_limit_event_dedup (
    dedup_key   VARCHAR(512) PRIMARY KEY,
    event_id    UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT risk_limit_event_dedup_created_at_plausible CHECK (created_at >= TIMESTAMPTZ '2020-01-01')
);

INSERT INTO risk_limit_event_dedup (dedup_key, event_id, created_at)
SELECT dedup_key, event_id, created_at FROM risk_outbox
ON CONFLICT (dedup_key) DO NOTHING;
