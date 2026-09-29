-- SPDX-License-Identifier: Apache-2.0
-- ADR-0315 D10: an AI agent may draft a deal, and every agent draft stores the inputs it used next
-- to its rationale, so the human who submits it and the approver who books it can check the
-- proposal against what the agent actually saw. A JSON object, kept as TEXT (the service reads and
-- returns it verbatim; nothing queries inside it). Nullable: a human dealer's draft carries none.
--
-- V9 because V5-V8 are claimed by the open treasury PRs (limit utilisation, FX spot, nostro,
-- lombard). This must merge AFTER them: Flyway refuses an older version arriving after a newer one
-- has been applied (out-of-order is off).
--
-- Rollback: ALTER TABLE deals DROP COLUMN draft_inputs;

ALTER TABLE deals ADD COLUMN draft_inputs TEXT;
