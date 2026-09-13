-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0283 D6: an agent-initiated card purchase records WHICH agent acted.
--
-- ROLLBACK: ALTER TABLE card_authorizations DROP COLUMN initiated_by_agent_id;
-- Nullable and unread by any existing query, so the drop loses only the attribution written after
-- this migration was applied. No data migration to undo.

-- NULL means a human purchase, which is every row written before this column existed and the
-- ordinary case after it. Deliberately NOT defaulted to a placeholder: "no agent was acting" and
-- "an agent was acting and we did not record which" are different facts, and a default would make
-- the first indistinguishable from the second for every historical row.
ALTER TABLE card_authorizations
    ADD COLUMN initiated_by_agent_id VARCHAR(128);

-- Partial: the column is null for almost every row, and an agentic-spend query only ever asks for
-- the ones where it is set. A full index would be mostly nulls and would slow every ordinary insert
-- on the money path for a lookup nobody makes.
CREATE INDEX ix_card_authorizations_agent
    ON card_authorizations (initiated_by_agent_id, authorized_at DESC)
    WHERE initiated_by_agent_id IS NOT NULL;
