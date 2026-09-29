-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
-- A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
-- See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
--
-- ADR-0244 D7: local projection of durable agent kill-switch events and explicit no-action state.
-- Rollback: DROP TABLE case_kill_switch; ALTER TABLE case_workflow DROP COLUMN halted_by,
--   DROP COLUMN halted_at, DROP COLUMN halt_reason, DROP COLUMN halt_scope;

CREATE TABLE case_kill_switch (
    scope       VARCHAR(64) PRIMARY KEY,
    reason      VARCHAR(255) NOT NULL,
    set_by      VARCHAR(255) NOT NULL,
    source_event_id UUID NOT NULL,
    set_at      TIMESTAMPTZ NOT NULL,
    removed_at  TIMESTAMPTZ
);

ALTER TABLE case_workflow
    ADD COLUMN halted_by VARCHAR(255),
    ADD COLUMN halted_at TIMESTAMPTZ,
    ADD COLUMN halt_reason VARCHAR(255),
    ADD COLUMN halt_scope VARCHAR(64);
