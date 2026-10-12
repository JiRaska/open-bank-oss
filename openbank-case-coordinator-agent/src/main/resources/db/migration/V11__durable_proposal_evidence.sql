-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
-- A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
-- See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
--
-- Proposal metadata must remain readable after the delivery-buffer row is purged (#11896).
-- Keep the SENT-retention exemption until historical coverage and this separate evidence store's
-- retention policy have been reviewed. No payload is copied into this table.
-- Rollback before purging outbox rows: DROP TRIGGER case_proposal_evidence_capture ON case_outbox;
-- DROP FUNCTION capture_case_proposal_evidence(); DROP TABLE case_proposal_evidence;

CREATE TABLE case_proposal_evidence (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    delivery_status VARCHAR(16) NOT NULL,
    emitted_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_case_proposal_evidence_aggregate_time
    ON case_proposal_evidence (aggregate_id, emitted_at, event_id);

INSERT INTO case_proposal_evidence (event_id, aggregate_id, event_type, delivery_status, emitted_at)
SELECT event_id, aggregate_id, event_type, status, created_at FROM case_outbox;

CREATE FUNCTION capture_case_proposal_evidence() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO case_proposal_evidence (event_id, aggregate_id, event_type, delivery_status, emitted_at)
    VALUES (NEW.event_id, NEW.aggregate_id, NEW.event_type, NEW.status, NEW.created_at)
    ON CONFLICT (event_id) DO UPDATE SET delivery_status = EXCLUDED.delivery_status;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER case_proposal_evidence_capture
    AFTER INSERT OR UPDATE OF status ON case_outbox
    FOR EACH ROW EXECUTE FUNCTION capture_case_proposal_evidence();
