-- SPDX-License-Identifier: Apache-2.0
-- Servicing posting loop (ADR-0028 Phase 2): accrual-basis interest recognition.
-- Each installment's interest is recognized as income once it falls due (IAS 1 accrual basis),
-- independent of cash collection; the flag makes the scheduled accrual pass idempotent.

ALTER TABLE installment
    ADD COLUMN interest_accrued BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN accrued_at       TIMESTAMPTZ;

-- Drives the scheduled accrual pass: due, unpaid, not yet accrued.
CREATE INDEX idx_installment_accruable
    ON installment(due_date)
    WHERE paid = FALSE AND interest_accrued = FALSE;
