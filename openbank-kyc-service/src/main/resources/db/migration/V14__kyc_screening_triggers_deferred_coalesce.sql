-- SPDX-License-Identifier: Apache-2.0
-- ADR-0256 / issue #15961: one switch (openbank.kyc.screening-triggers.enabled) decides whether
-- the trigger loop opens cases; the queue records either way. While the switch is off the
-- consumer writes a DEFERRED row per candidate party (state PENDING, attempts 0, no coordinator
-- call yet) and the retry job drains it once the switch is on.
--
-- A deferred row is a request to re-screen the party against the CURRENT lists, so one per party
-- is enough however many publications arrive while off. This partial unique index is the
-- coalescing key: `INSERT ... ON CONFLICT DO NOTHING` keeps the first deferred row and drops the
-- rest. A row leaves the index the moment the retry job claims it (attempts becomes 1), so a
-- list change arriving after that queues a fresh one. Rows written with the switch ON start at
-- attempts 1 and are never in the index.
--
-- Rollback: DROP INDEX uq_kyc_screening_triggers_deferred_party; (no data change).
CREATE UNIQUE INDEX uq_kyc_screening_triggers_deferred_party
    ON kyc_screening_triggers (party_id)
    WHERE state = 'PENDING' AND attempts = 0;
