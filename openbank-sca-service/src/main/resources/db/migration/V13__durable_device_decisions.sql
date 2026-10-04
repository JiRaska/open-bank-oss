-- SPDX-License-Identifier: Apache-2.0
-- Rollback: retain the table and signature evidence. Pause initiation and drain all unexpired
-- challenges before replacing every writer with an older binary; Redis has no copy of these decisions.
-- Retain signature evidence after authorization expiry, until the configured retention period ends.
-- Rollout/rollback require draining unexpired challenges before switching decision stores.
CREATE TABLE sca_device_decisions (
    challenge_id UUID PRIMARY KEY REFERENCES sca_challenges(id),
    credential_id VARCHAR(512) NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVED', 'DENIED')),
    signature_b64 TEXT NOT NULL,
    signed_payload_b64 TEXT NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    challenge_version INTEGER NOT NULL,
    -- #10281 item 3: whose enrolled device signed; nullable for parity with DeviceApprovalDecision.
    deciding_party_id UUID,
    CHECK (expires_at > decided_at)
);

-- Retention purge predicate (decided_at < now() - retention), oldest first in bounded batches.
-- Evidence is deleted only by DecisionEvidencePurgeScheduler after openbank.sca.decision-retention-days.
CREATE INDEX idx_sca_device_decisions_decided_at ON sca_device_decisions (decided_at);
