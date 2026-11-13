-- SPDX-License-Identifier: Apache-2.0
-- ADR-0256 / issue #15961: one switch (openbank.kyc.screening-triggers.enabled) decides whether
-- the trigger loop opens cases; the queue records either way. While the switch is off the
-- consumer writes a row in the new state DEFERRED per candidate party (no attempt, no
-- coordinator call); the retry job claims it (DEFERRED -> PENDING with a lease, atomically) once
-- the switch is on.
--
-- DEFERRED is a state of its own ON PURPOSE: code from before this migration selects and claims
-- only state IN ('PENDING','OPEN_FAILED'), so after an image rollback the old retry job (which
-- ignores the master switch) cannot see these rows and cannot open cases with the switch off.
-- `state` is VARCHAR(20) with no CHECK constraint (V12), so no constraint change is needed.
--
-- A deferred row is a request to re-screen the party against the CURRENT lists, so one per party
-- is enough however many publications arrive while off. This partial unique index is the
-- coalescing key: `INSERT ... ON CONFLICT DO NOTHING` keeps the first deferred row and drops the
-- rest. A row leaves the index the moment the retry job claims it.
--
-- Rollback: DROP INDEX uq_kyc_screening_triggers_deferred_party; DEFERRED rows stay inert under
-- the old code (they are invisible to it) and are drained when this code returns.
CREATE UNIQUE INDEX uq_kyc_screening_triggers_deferred_party
    ON kyc_screening_triggers (party_id)
    WHERE state = 'DEFERRED';
