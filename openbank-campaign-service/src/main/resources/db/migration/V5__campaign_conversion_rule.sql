-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

-- ADR-0245 D1: a campaign gains an optional conversion rule — a catalogue key, not a query and not
-- free text. The rule itself (topic, accepted event types, attribution window) lives in domain code
-- (ConversionCatalog), so this column stores only the key and a rule can be corrected by a code
-- review rather than a data migration.
-- Nullable: every existing campaign has no conversion rule and reads back exactly as before, which
-- is also the honest resting state — most campaigns will have none until the catalogue grows.
-- Rollback: ALTER TABLE campaigns DROP COLUMN conversion_rule;
ALTER TABLE campaigns ADD COLUMN conversion_rule varchar(64);
