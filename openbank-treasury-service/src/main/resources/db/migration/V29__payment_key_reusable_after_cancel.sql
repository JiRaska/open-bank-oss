-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D6: a CANCELLED payment frees its flows "for a new proposal", but the table-wide UNIQUE
-- on idempotency_key made that re-proposal impossible (the same flows derive the same key), so a
-- payment refused SSI_CHANGED could never be re-addressed. The key stays unique among every
-- payment that is not CANCELLED — a cancelled one never reached any rail, so the rail never saw
-- the key, and two live payments for the same flows remain impossible.
--
-- Rollback: DROP INDEX uq_treasury_payments_live_key; ALTER TABLE treasury_payments ADD CONSTRAINT
-- treasury_payments_idempotency_key_key UNIQUE (idempotency_key) (fails while a cancelled and a
-- live payment share a key: delete or re-key the cancelled row first).

ALTER TABLE treasury_payments DROP CONSTRAINT treasury_payments_idempotency_key_key;
CREATE UNIQUE INDEX uq_treasury_payments_live_key ON treasury_payments (idempotency_key) WHERE status <> 'CANCELLED';
CREATE INDEX idx_treasury_payments_key ON treasury_payments (idempotency_key);
