-- SPDX-License-Identifier: Apache-2.0
-- ADR-0211 D7 (2/2): cut the pre-canonical application statuses over to the canonical
-- origination graph. Existing rows were all created through the API submission flow,
-- so PROPOSED maps to SUBMITTED (created_at IS the submission timestamp); APPROVED to
-- OFFERED (approved, not yet disbursed); REJECTED to DECLINED; DISBURSED is stable.
-- Adds the jurisdiction/product_type/pack_version columns the compliance-pack guard
-- and pinning (ADR-0212 D2/D3) read; nullable because pre-canonical rows predate packs.
--
-- Rollback: UPDATE loan_application SET status = CASE status
--             WHEN 'SUBMITTED' THEN 'PROPOSED' WHEN 'DRAFT' THEN 'PROPOSED'
--             WHEN 'OFFERED' THEN 'APPROVED' WHEN 'DECLINED' THEN 'REJECTED'
--             ELSE status END;
--           ALTER TABLE loan_application DROP COLUMN jurisdiction,
--             DROP COLUMN product_type, DROP COLUMN pack_version;

UPDATE loan_application
SET status = CASE status
    WHEN 'PROPOSED' THEN 'SUBMITTED'
    WHEN 'APPROVED' THEN 'OFFERED'
    WHEN 'REJECTED' THEN 'DECLINED'
    ELSE status
END;

ALTER TABLE loan_application
    ADD COLUMN jurisdiction VARCHAR(8),
    ADD COLUMN product_type VARCHAR(32),
    ADD COLUMN pack_version INT;
