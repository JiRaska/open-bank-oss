-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
-- A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
-- See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
--
-- ADR-0244 Phase 2 (#4185): columns the case-thread read model projects.
-- case_workflow.workflow_id: the external case id (Temporal workflow id, e.g.
--   case-incident-response-<subject>) — the PK is a derived UUID (nameUUIDFromBytes) which is not
--   reversible, so the read API cannot hand callers their id back without this column.
-- case_contribution.{summary,draft_version,contested,evidence_refs}: the contribution content the
--   ADR-0246 thread view renders. V1 recorded only bookkeeping (agent, tokens, preemption flag);
--   the content lived solely in Temporal workflow state, which a read API must not depend on.
-- Rollback: ALTER TABLE case_contribution DROP COLUMN evidence_refs, DROP COLUMN contested,
--   DROP COLUMN draft_version, DROP COLUMN summary; ALTER TABLE case_workflow DROP COLUMN workflow_id;
--   (the Phase 2 read API degrades to 404/empty; write paths keep working)

ALTER TABLE case_workflow
    ADD COLUMN workflow_id VARCHAR(255);

ALTER TABLE case_contribution
    ADD COLUMN summary       TEXT,
    ADD COLUMN draft_version INTEGER,
    ADD COLUMN contested     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN evidence_refs JSONB;

-- The workflow id is the read API's lookup key for both the list filter and the detail path.
CREATE UNIQUE INDEX idx_case_workflow_workflow_id ON case_workflow (workflow_id);
