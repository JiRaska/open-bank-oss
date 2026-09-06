-- Natural-key idempotency for POST /api/v1/balances/{accountId}/holds (ADR-0287, burn-down #8351).
-- The caller-supplied referenceId names one durable business fact (a payment authorisation), so the
-- triple (account_id, currency, reference_id) identifies one hold. Before this index a retried
-- placeHold reserved TWICE — a real money effect (the amount stays unavailable until expiry or
-- release). The service checks the key first; this index is the race backstop for two concurrent
-- first attempts, and the unique violation is recovered by re-reading the winning row.
--
-- Rollback: DROP INDEX IF EXISTS uq_balance_holds_reference;
-- (Reversible: dropping the index re-opens the double-reservation window for concurrent retries but
--  corrupts nothing; the check-first path keeps sequential retries safe.)
CREATE UNIQUE INDEX uq_balance_holds_reference
    ON balance_holds (account_id, currency, reference_id);
