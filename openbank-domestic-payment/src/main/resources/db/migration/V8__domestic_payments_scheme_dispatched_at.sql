-- #4218: re-driving a payment stranded in VALIDATED submitted it to the clearing scheme a SECOND
-- time, creating two clearing items for one payment.
--
-- The rail could not tell "never submitted" from "submitted, bookkeeping failed", because both are
-- spelled `status = VALIDATED`. submitScheme wrapped BOTH the outbound pacs.008 and the follow-up
-- paymentRepository.update in one try/catch, so a database failure after a successful submit was
-- caught and logged as "holding in VALIDATED" — leaving a live clearing item behind a row that
-- claims nothing was ever sent. A re-drive then reads that row and submits again. Nothing
-- downstream dedups it: SchemeGatewayAdapter sends no idempotency key, and openbank-clearing-
-- simulator has no dedup of any kind (checked: no idempotency/duplicate handling in its source).
--
-- This column records the dispatch itself, written BEFORE the gateway call and in its own
-- transaction, so it survives any failure of the work that follows. It is deliberately NOT
-- `submitted_at`: that column is already set on the transition to VALIDATED (see
-- DomesticPayment.transitionTo), so it is non-null for every payment that could reach this path
-- and can carry no information about the scheme hop.
--
-- Semantics: non-null means a pacs.008 was handed to the gateway and we do not know the outcome
-- was recorded. The rail refuses to submit such a payment again and holds it for reconciliation.
-- It is cleared only when the gateway proves the request never left (connection refused / unknown
-- host), which is the ordinary "scheme is down" case and must stay re-drivable. An ambiguous
-- failure — a timeout above all — keeps the marker: for an outbound money instruction, a strand an
-- operator can see is the correct trade against a duplicate payment nobody can recall.
--
-- ROLLBACK: ALTER TABLE domestic_payments DROP COLUMN scheme_dispatched_at;
-- Safe in the sense that nothing else reads it, but note what dropping it restores: the rail goes
-- back to being unable to distinguish the two states, i.e. the #4218 defect itself.
ALTER TABLE domestic_payments
    ADD COLUMN scheme_dispatched_at TIMESTAMPTZ;

-- Partial: the reconciliation question is only ever asked of payments that are still VALIDATED and
-- carry a dispatch marker — a tiny slice of a table that is mostly settled. Also the query an
-- operator runs to find what #4218 stranded.
CREATE INDEX idx_domestic_payments_dispatched_unconfirmed
    ON domestic_payments (scheme_dispatched_at)
    WHERE status = 'VALIDATED' AND scheme_dispatched_at IS NOT NULL;
