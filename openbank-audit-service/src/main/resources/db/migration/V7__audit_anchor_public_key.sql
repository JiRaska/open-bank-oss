-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

-- Preserve the asymmetric public verification material with each anchor generation. This keeps
-- third-party verification possible after the KMS alias moves to a replacement key.
-- Rollback: ALTER TABLE audit_anchor DROP COLUMN public_key_pem;
ALTER TABLE audit_anchor ADD COLUMN IF NOT EXISTS public_key_pem TEXT;
