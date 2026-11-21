-- SPDX-License-Identifier: Apache-2.0
-- One row means AccountCreated was observed. A NULL context is an unclassified opening.
-- Rollback: disable the consumer first and retain the projection for deterministic replay.
CREATE TABLE account_booking_projection (
    account_id UUID NOT NULL,
    synthetic BOOLEAN NOT NULL,
    PRIMARY KEY (account_id, synthetic),
    booking_context JSONB,
    CONSTRAINT chk_account_booking_projection_context
        CHECK (booking_context IS NULL OR jsonb_typeof(booking_context) = 'object')
);
