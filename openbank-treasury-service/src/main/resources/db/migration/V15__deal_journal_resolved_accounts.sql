-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0341 D3: treasury posts by accounting class and the ledger resolves the GL account. A reversal
-- must offset the account the original posting landed on even if the ledger's map is re-pointed in
-- between, so each journal records what its legs resolved to (one `class|role|currency|glAccountId`
-- per line). NULL on journals posted before this column existed: their reversal re-resolves the class.
-- Rollback: ALTER TABLE deal_journals DROP COLUMN resolved_accounts (reversals then re-resolve).
ALTER TABLE deal_journals ADD COLUMN resolved_accounts TEXT;
