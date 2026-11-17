-- SPDX-License-Identifier: Apache-2.0
-- Nullable expansion: the historic attribution of existing journals is unknown.
-- Rollback: deploy the previous application and keep these columns as durable evidence.
-- V44's whole-row immutability guard covers the new columns after posting.
ALTER TABLE journal_entries
    ADD COLUMN booking_company_id uuid,
    ADD COLUMN booking_context_version_id uuid,
    ADD COLUMN booking_mode varchar(8),
    ADD COLUMN booking_branch_id uuid,
    ADD CONSTRAINT journal_booking_attribution_shape CHECK (
        (booking_company_id IS NULL AND booking_context_version_id IS NULL
            AND booking_mode IS NULL AND booking_branch_id IS NULL)
        OR
        (booking_company_id IS NOT NULL AND booking_context_version_id IS NOT NULL
            AND booking_mode IS NOT NULL AND booking_mode IN ('DOMESTIC', 'FOS', 'BRANCH')
            AND ((booking_mode = 'BRANCH') = (booking_branch_id IS NOT NULL)))
    ) NOT VALID;
