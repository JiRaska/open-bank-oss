-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D6 / contract section 9: release is asynchronous and atomic. The release transaction
-- stores the institution-transfer rail it chose from the SSI (CZK -> CERTIS, EUR -> TARGET2,
-- other -> SWIFT) and writes treasury.payment.released.v1 to the outbox; the rail's status events
-- move the payment to SENT / SETTLED / FAILED. The intermediary BIC of the SSI is carried so the
-- released event is complete without re-reading reference data that may have changed since.
--
-- Rollback: ALTER TABLE treasury_payments DROP COLUMN transfer_rail, DROP COLUMN intermediary_bic;
-- no data migration (both columns are NULL on every payment released before this version).

ALTER TABLE treasury_payments
    ADD COLUMN transfer_rail    VARCHAR(8) CHECK (transfer_rail IN ('CERTIS', 'TARGET2', 'SWIFT')),
    ADD COLUMN intermediary_bic VARCHAR(11);
