-- Durable simulator verdict: the unique rail + submission identity is the concurrency arbiter.
-- No payment is submitted to a real network by this service.
CREATE TABLE clearing_submission_receipt (
    rail VARCHAR(32) NOT NULL,
    submission_id VARCHAR(70) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    status_report_xml TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (rail, submission_id),
    CONSTRAINT clearing_receipt_rail_nonempty CHECK (length(rail) > 0),
    CONSTRAINT clearing_receipt_submission_nonempty CHECK (length(submission_id) > 0)
);
