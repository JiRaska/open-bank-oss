-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

-- ADR-0200 D1 stop conditions (#3585 slice 1): the campaign definition gains an optional stop
-- condition, evaluated by the journey workflow before each step against the send log's SENT count.
-- Nullable: existing campaigns have no stop condition and read back exactly as before.
-- Rollback: ALTER TABLE campaigns DROP COLUMN stop_condition_json;
ALTER TABLE campaigns ADD COLUMN stop_condition_json text;
