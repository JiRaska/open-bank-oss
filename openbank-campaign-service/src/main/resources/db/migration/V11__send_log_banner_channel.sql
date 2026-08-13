-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

ALTER TABLE send_log DROP CONSTRAINT IF EXISTS send_log_channel_check;
ALTER TABLE send_log
    ADD CONSTRAINT send_log_channel_check CHECK (channel IN ('EMAIL', 'PUSH', 'BANNER'));

-- Rollback: replace the constraint with `channel IN ('EMAIL', 'PUSH')` after removing BANNER rows.
