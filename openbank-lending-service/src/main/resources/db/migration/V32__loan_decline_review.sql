-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Customer-requested human review of a decision-engine decline (GDPR Art. 22(3), EU AI Act Art. 86,
-- EBA/GL/2020/06 par. 54). The declined application stays DECLINED (terminal); the review is its own
-- record, one per application. An OVERTURNED review points at a successor application that enters the
-- existing four-eyes decision (the predecessor link on loan_application arrives with V33).
-- Rollback: DROP TABLE loan_decline_review. Destructive for review evidence: the review rows are the
-- record that a customer asked, so roll back only before the first review is written; afterwards stop
-- the endpoints.
CREATE TABLE loan_decline_review (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE REFERENCES loan_application (id),
    party_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    engine_outcome VARCHAR(16) NOT NULL,
    engine_reasons TEXT,
    policy_versions TEXT,
    engine_decided_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    reviewed_by VARCHAR(128),
    reviewed_at TIMESTAMPTZ,
    review_reason VARCHAR(512),
    successor_application_id UUID UNIQUE REFERENCES loan_application (id),
    CONSTRAINT ck_loan_decline_review_status CHECK (status IN ('REQUESTED', 'CONFIRMED', 'OVERTURNED')),
    -- A decided review always names its reviewer and a reason; a pending one names neither.
    CONSTRAINT ck_loan_decline_review_decided CHECK (
        (status = 'REQUESTED' AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_reason IS NULL)
        OR (status <> 'REQUESTED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
            AND length(btrim(review_reason)) >= 10)
    ),
    CONSTRAINT ck_loan_decline_review_successor CHECK ((status = 'OVERTURNED') = (successor_application_id IS NOT NULL))
);

CREATE INDEX ix_loan_decline_review_pending ON loan_decline_review (requested_at) WHERE status = 'REQUESTED';
