-- SPDX-License-Identifier: Apache-2.0
-- Publication state is mandatory even when a list has no entries or pending changes.
-- Lists introduced after V15 also need their own refresh generation and publication lock.
-- Preserve existing fingerprints and refresh fences, including incomplete active refreshes.
-- Rollback: retain these additive control rows when rolling back application code. Removing
-- a row can discard a generation/fence or fingerprint and break publication on older code.
-- Do not remove state for a retained list or discard pending journal/outbox evidence.
INSERT INTO sanctions_change_publication (list_type)
SELECT list_type FROM sanctions_lists
ON CONFLICT (list_type) DO NOTHING;
