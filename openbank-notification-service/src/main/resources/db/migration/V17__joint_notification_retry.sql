-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Expand-only: old notification-service pods ignore these nullable columns during rollout.
-- Rollback code first; retain the columns and retry evidence. A later reviewed contraction may
-- DROP INDEX idx_notifications_joint_pending_retry and DROP the three columns after all readers
-- have moved off them. Never delete notification rows as a rollback.
ALTER TABLE notifications ADD COLUMN delivery_not_after TIMESTAMPTZ;
ALTER TABLE notifications ADD COLUMN delivery_retry_claimed_at TIMESTAMPTZ;
ALTER TABLE notifications ADD COLUMN delivery_retry_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_notifications_joint_pending_retry
    ON notifications (created_at, id)
    WHERE status = 'PENDING'
      AND template IN ('JOINT_ISSUANCE_SIGNATURE_REQUESTED', 'JOINT_ACCEPTANCE_SIGNATURE_REQUESTED');
