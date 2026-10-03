-- SPDX-License-Identifier: Apache-2.0
-- Durable settlement maker/checker approvals (ADR-0155, #10041 slice 10). settlement.create is in
-- rules.yaml four_eyes.actions; where authz.four-eyes.enforce=true AuthorizeInterceptor parks the
-- maker's POST /api/v1/settlements with 202 and a PENDING row here, bound to the exact instruction
-- by request_fingerprint (endpoint + arguments, #11675). Only a retry of that identical request
-- may claim it, once. Every transition commits together with a
-- SETTLEMENT_OPERATOR_APPROVAL_CHANGED row on settlement_outbox, so maker, checker and claim are
-- retained evidence after the row itself ages out (OperatorApprovalRetention).
--
-- Rollback: retain this table and the outbox evidence (do not DROP). Set
-- AUTHZ_FOUR_EYES_ENFORCE=false (the default) to stop parking new originations, and let live
-- approvals expire before replacing writers. A rolled-back binary without PostgresApprovalStore
-- simply stops reading the table. Old binaries claim outbox rows with `RETURNING *`; the generated
-- settlement_ref column below is not mapped by them, so pause outbox dispatch on old pods before
-- migrating and resume once every dispatcher uses the explicit projection.
CREATE TABLE settlement_operator_approvals (
    id UUID PRIMARY KEY,
    action TEXT NOT NULL,
    resource_id TEXT,
    maker_id TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXECUTED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    decided_by TEXT,
    decided_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ,
    request_fingerprint VARCHAR(64),
    summary VARCHAR(1024),
    CHECK (expires_at > created_at),
    CHECK ((status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decided_by <> maker_id)),
    CHECK ((status = 'EXECUTED') = (claimed_at IS NOT NULL))
);

CREATE INDEX idx_settlement_operator_approvals_pending ON settlement_operator_approvals (expires_at, created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_settlement_operator_approvals_maker_pending
    ON settlement_operator_approvals (action, maker_id, expires_at)
    WHERE status = 'PENDING';

-- Retention (OperatorApprovalRetention): rows whose authorization expired before the cutoff are
-- deleted oldest first, in every status — an expired PENDING approval can never be decided or
-- claimed, so it is terminal. A still-live approval never matches (the cutoff lies in the past).
CREATE INDEX idx_settlement_operator_approvals_retention ON settlement_operator_approvals (expires_at, id);

-- settlement_outbox now carries two aggregate types. The settlement FK is kept for settlement
-- events through a generated column; approval events deliberately have NO FK, because the
-- approval row is purged after retention while its outbox evidence is kept.
ALTER TABLE settlement_outbox
    ADD COLUMN settlement_ref UUID GENERATED ALWAYS AS
        (CASE WHEN event_type = 'SETTLEMENT_STATE_CHANGED' THEN aggregate_id END) STORED
        REFERENCES settlements(id),
    ADD CONSTRAINT settlement_outbox_known_event_type CHECK
        (event_type IN ('SETTLEMENT_STATE_CHANGED', 'SETTLEMENT_OPERATOR_APPROVAL_CHANGED'));
ALTER TABLE settlement_outbox DROP CONSTRAINT settlement_outbox_aggregate_id_fkey;
