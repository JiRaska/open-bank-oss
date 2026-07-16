-- Operator-initiated customer messaging drafts and their approval lifecycle (ADR-0176).
--
-- ApprovalStore (the shared ADR-0155 four-eyes mechanism, RedisApprovalStore) only tracks
-- (action, resourceId, makerId, status) — it has no field for the actual message content, and
-- no query to list every pending approval. This table IS the persisted draft, keyed by its own
-- id as the ApprovalStore resourceId once the maker calls submit.
--
-- No separate DRAFT status: AuthorizeInterceptor short-circuits BEFORE the submit handler's
-- method body ever runs on a first (un-approved) call — it throws its own 202 response and
-- never reaches application code — so there is no hook inside the resource method to flip a
-- row from an earlier DRAFT state at the moment an approval becomes pending. A row is therefore
-- born PENDING_APPROVAL at creation and stays that way until it resolves. The checker's REJECT
-- decision explicitly flips it to REJECTED (the maker's own retry would never otherwise run to
-- record that, since ApprovalStore refuses a retry against a non-APPROVED decision); the maker's
-- successful retry (once approved) flips it to SENT after dispatch succeeds.
--
-- This table is also what backs the admin-ui "pending approvals" list
-- (WHERE status = 'PENDING_APPROVAL') — there is no other way to discover what needs a second
-- operator's attention, since ApprovalStore itself cannot be listed.
CREATE TABLE operator_messages (
    id             BIGSERIAL PRIMARY KEY,
    message_id     UUID NOT NULL UNIQUE,
    party_id       UUID NOT NULL,
    template       VARCHAR(64) NOT NULL,
    -- ADR-0176 D2: the only parameter the sole MVP template (OPERATOR_ACCOUNT_NOTICE) takes,
    -- validated server-side (^[A-Za-z0-9-]{1,40}$) before this row is ever written — never
    -- operator-supplied prose. A generic variables map is deliberately NOT used here: the whole
    -- point of the catalogue design is that each template's parameters are named and typed, not
    -- an arbitrary bag of strings. A second template needing more than one parameter is a new
    -- nullable column (or, if the shape genuinely varies per template, a JSONB migration at that
    -- point) — not a reason to generalise ahead of the second real use case.
    reference_id   VARCHAR(40) NOT NULL,
    -- SERVICE | LEGAL | MARKETING (ADR-0176 D6). MARKETING is rejected at the compose endpoint
    -- before a row is ever written — this column exists so the lawful basis is still explicit
    -- and auditable for the two purposes that ARE allowed, not because MARKETING rows occur.
    purpose        VARCHAR(16) NOT NULL,
    -- PENDING_APPROVAL (from creation) -> SENT, or PENDING_APPROVAL -> REJECTED.
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL',
    -- The maker's identity.principal.name (preferred_username) — MUST match the format
    -- AuthorizeInterceptor.buildQuery uses for ApprovalStore's makerId, or the self-approval
    -- guard (SelfApprovalNotAllowedException) could silently fail to catch a maker approving
    -- their own request (same trap ledger-service's ApprovalResource comments on).
    maker_id       VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Discovery path for the second operator: "what needs my approval right now".
CREATE INDEX idx_operator_messages_status ON operator_messages(status);
-- Party message history (admin-ui party detail tab reads notifications, not this table
-- directly, but a party-scoped lookup here is the natural audit/debug path).
CREATE INDEX idx_operator_messages_party ON operator_messages(party_id);

-- Hibernate Reactive + Kotlin PanacheEntity allocates the id from a sequence named
-- "<table>_seq" (default allocationSize 50); BIGSERIAL alone only yields
-- "operator_messages_id_seq". Without this every INSERT fails with: relation
-- "operator_messages_seq" does not exist. Same convention as V5__notification_sequences.sql /
-- V6__create_device_tokens.sql; enforced by HibernateSequenceGuardTest.
-- Rollback: DROP TABLE operator_messages; DROP SEQUENCE operator_messages_seq.
-- No data-loss note beyond the ordinary one: every row here is either an unsent draft or a
-- record of a message that was ALSO persisted to `notifications` on send, so nothing here is
-- the only copy of anything that reached a customer.
CREATE SEQUENCE IF NOT EXISTS operator_messages_seq INCREMENT BY 50;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
