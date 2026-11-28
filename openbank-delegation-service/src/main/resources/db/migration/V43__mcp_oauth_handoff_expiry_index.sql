-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

-- Rollback: leave the index in place while the retention job is enabled. It can be
-- dropped in a forward migration after the job is disabled.
CREATE INDEX mcp_oauth_handoffs_expiry_idx ON mcp_oauth_handoffs (expires_at, token_sha256);
