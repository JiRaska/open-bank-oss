-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Rollback: stop using the review endpoint. Retain reviews and their audit history.

CREATE TABLE legal_inbox_review (
    receipt_id UUID PRIMARY KEY REFERENCES legal_delivery_inbox (receipt_id),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    team VARCHAR(32) CHECK (team IN ('ENFORCEMENT_CZ', 'LEGAL_REVIEW_CZ')),
    extraction_status VARCHAR(32) CHECK (extraction_status IN ('DRAFT', 'MANUAL_REVIEW')),
    subject VARCHAR(256),
    sender_reference VARCHAR(128),
    attachment_count INTEGER CHECK (attachment_count >= 0)
);

CREATE TABLE legal_inbox_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    receipt_id UUID NOT NULL REFERENCES legal_delivery_inbox (receipt_id),
    actor_subject VARCHAR(255) NOT NULL,
    action VARCHAR(32) NOT NULL CHECK (action IN ('DOWNLOAD_MESSAGE', 'DOWNLOAD_DELIVERY', 'EXTRACT', 'READ_DRAFT', 'ASSIGN')),
    previous_team VARCHAR(32),
    next_team VARCHAR(32),
    review_revision INTEGER NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX legal_inbox_audit_receipt ON legal_inbox_audit (receipt_id, id);
CREATE INDEX legal_inbox_mailbox_order ON legal_delivery_inbox (source_namespace, recorded_at DESC, receipt_id DESC);
