-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 catalogue: close-of-day (and special) position snapshots a back-office user records, and
-- each user's saved blotter filters. Both are views over the deals; neither changes a deal.
--
-- Rollback: DROP TABLE deal_filters, position_closes; DROP SEQUENCE position_closes_seq, deal_filters_seq;
CREATE SEQUENCE IF NOT EXISTS position_closes_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE position_closes (
    id             BIGINT       PRIMARY KEY,
    snapshot_id    UUID         NOT NULL UNIQUE,
    close_date     DATE         NOT NULL,
    book_id        VARCHAR(64),
    scenario       VARCHAR(64),
    special        BOOLEAN      NOT NULL,
    closed_by      VARCHAR(255) NOT NULL,
    closed_at      TIMESTAMPTZ  NOT NULL,
    positions_json TEXT         NOT NULL,
    command_key    VARCHAR(128) NOT NULL UNIQUE
);

CREATE SEQUENCE IF NOT EXISTS deal_filters_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE deal_filters (
    id          BIGINT       PRIMARY KEY,
    owner       VARCHAR(255) NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    filter_json TEXT         NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT deal_filters_owner_name UNIQUE (owner, name)
);
