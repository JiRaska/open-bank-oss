-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
-- A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
-- See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
--
-- ADR-0271: server-owned delivery mode plus separately observable authorization, invocation,
-- consumption and persistence stages for case collaboration. Payload content is deliberately absent.
-- Rollback: DROP TABLE case_signal_evidence; ALTER TABLE case_workflow DROP COLUMN delivery_mode;

ALTER TABLE case_workflow
    ADD COLUMN delivery_mode VARCHAR(16) NOT NULL DEFAULT 'HITL';

CREATE TABLE case_signal_evidence (
    signal_id         UUID         NOT NULL,
    case_id           UUID         NOT NULL REFERENCES case_workflow(id) ON DELETE CASCADE,
    agent_id          VARCHAR(64)  NOT NULL,
    capability        VARCHAR(32)  NOT NULL,
    stage             VARCHAR(16)  NOT NULL,
    observed_at       TIMESTAMPTZ  NOT NULL,
    rollout_id        VARCHAR(128),
    policy_decision_id VARCHAR(128),
    policy_reason     VARCHAR(255),
    PRIMARY KEY (signal_id, stage)
);

CREATE INDEX idx_case_signal_evidence_case_time
    ON case_signal_evidence (case_id, observed_at);
