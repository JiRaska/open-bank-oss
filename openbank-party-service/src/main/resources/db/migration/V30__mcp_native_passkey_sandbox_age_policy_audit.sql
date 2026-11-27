-- SPDX-License-Identifier: Apache-2.0
-- Effective policy and audit are the same append-only row. A null rule is a revocation.
-- Rollback: disable both sandbox controls and retain this table for audit.
CREATE TABLE mcp_native_passkey_sandbox_age_policy_audit (
    sequence_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_subject text NOT NULL CHECK (length(actor_subject) BETWEEN 1 AND 255),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    minimum_age_minutes integer CHECK (minimum_age_minutes BETWEEN 1 AND 4319),
    expires_at timestamptz,
    reason text CHECK (length(reason) BETWEEN 1 AND 240),
    CHECK ((minimum_age_minutes IS NULL AND expires_at IS NULL AND reason IS NULL)
        OR (minimum_age_minutes IS NOT NULL AND expires_at IS NOT NULL AND reason IS NOT NULL))
);
