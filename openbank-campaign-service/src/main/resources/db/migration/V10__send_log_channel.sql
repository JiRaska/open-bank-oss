-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

-- The actual medium is audit evidence for a consent-triggered EMAIL → PUSH fallback. Nullable
-- preserves historic rows and non-send decisions (conversion, condition skip, policy suppression).
ALTER TABLE send_log
    ADD COLUMN channel TEXT CHECK (channel IN ('EMAIL', 'PUSH'));

-- Rollback: ALTER TABLE send_log DROP COLUMN channel;
