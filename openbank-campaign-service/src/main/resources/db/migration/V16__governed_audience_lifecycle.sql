-- SPDX-License-Identifier: Apache-2.0
-- A marketer-created audience is a typed, versioned segment that needs its own maker/checker
-- lifecycle. Existing rows predate an authoring path; retain their historical targetability as
-- approved legacy versions rather than changing any campaign reference during the rollout.
--
-- ROLLBACK:
-- The added columns are additive. A binary rollback may ignore them safely; remove them only after
-- no deployment needs the governed-audience lifecycle and after retaining the audit data elsewhere.

ALTER TABLE segments ADD COLUMN state TEXT NOT NULL DEFAULT 'APPROVED';
ALTER TABLE segments ADD COLUMN created_by TEXT NOT NULL DEFAULT 'legacy-catalogue';
ALTER TABLE segments ADD COLUMN approved_by TEXT;
ALTER TABLE segments ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_segments_state_name_version ON segments (state, name, version);
