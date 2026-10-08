-- SPDX-License-Identifier: Apache-2.0
-- ADR-0333 D4: locate stale ambiguous notification handoffs without scanning terminal history.
-- Rollback: DROP INDEX idx_notifications_pending_created_at; the gauge still works but costs more.
CREATE INDEX idx_notifications_pending_created_at ON notifications (created_at)
    WHERE status = 'PENDING';
