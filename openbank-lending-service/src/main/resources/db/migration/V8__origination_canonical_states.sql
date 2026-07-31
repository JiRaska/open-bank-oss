-- SPDX-License-Identifier: Apache-2.0
-- ADR-0211 D7 (1/2): widen the application_status enum with the canonical origination
-- states. PostgreSQL commits each ALTER TYPE ... ADD VALUE at the end of THIS migration,
-- so V9 can update rows onto the new values in its own transaction.
--
-- Rollback: none — PostgreSQL cannot drop individual enum values. The rollback for the
-- data half is documented in V9; new values are inert until used.

ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'DRAFT';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'SUBMITTED';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'KYC_PENDING';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'DOCS_REQUIRED';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'ASSESSMENT';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'DECISION_PENDING';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'FOUR_EYES';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'OFFERED';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'AWAITING_SIGNATURE';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'SIGNED';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'REFLECTION_PERIOD';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'READY_TO_DISBURSE';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'WITHDRAWN';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'DECLINED';
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'EXPIRED';
