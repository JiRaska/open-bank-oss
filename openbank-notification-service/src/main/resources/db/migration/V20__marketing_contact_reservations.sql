-- SPDX-License-Identifier: Apache-2.0
-- ADR-0333 D2: a durable, per-intent contact reservation at the outbound marketing choke point.
-- Rollback: DROP TABLE marketing_contact_reservations; keep marketing dispatch disabled while
-- reverting, because the old local counter cannot protect concurrent cross-origin sends.
CREATE TABLE marketing_contact_reservations (
    notification_id UUID PRIMARY KEY REFERENCES notifications(notification_id) ON DELETE CASCADE,
    party_id UUID NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_marketing_contact_reservations_party_window
    ON marketing_contact_reservations (party_id, reserved_at DESC);

GRANT ALL ON marketing_contact_reservations TO openbank;
