-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0367 / #15292: the CEÚ account-register projection, kept bitemporally as APPEND-ONLY facts.
-- effective_on is valid time (the business date the change took effect), recorded_at is record
-- time (when this service learnt it). A snapshot "register on day D as known at T" is a fold over
-- the rows with effective_on <= D and recorded_at <= T, done in the domain (RegisterSnapshot.fold).
-- Rows are never updated: a correction is a new fact, so what any past overview saw stays
-- reproducible.
--
-- No personal data is stored here: identifiers only. Names, birth numbers and addresses are read
-- from party-service at render time (ADR-0118).
--
-- synthetic carries the ADR-0252 taint of the producing event; a tainted fact never reaches a
-- return, and the fold counts what it drops.
--
-- Rollback: DROP TABLE ceu_register_fact; (no other table references it).
CREATE TABLE ceu_register_fact (
    event_id       UUID         PRIMARY KEY,
    fact_type      VARCHAR(32)  NOT NULL,
    account_id     UUID         NOT NULL,
    relation_id    UUID,
    party_id       UUID,
    role           VARCHAR(32),
    account_number VARCHAR(64),
    currency       CHAR(3),
    effective_on   DATE         NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL,
    synthetic      BOOLEAN      NOT NULL,
    CONSTRAINT ck_ceu_fact_type CHECK (fact_type IN ('ACCOUNT_OPENED', 'ACCOUNT_CLOSED', 'RELATION_STARTED', 'RELATION_ENDED')),
    CONSTRAINT ck_ceu_relation_shape CHECK (
        (fact_type IN ('RELATION_STARTED', 'RELATION_ENDED')) = (relation_id IS NOT NULL)
    )
);

CREATE INDEX ix_ceu_fact_time ON ceu_register_fact (effective_on, recorded_at);
CREATE INDEX ix_ceu_fact_relation ON ceu_register_fact (relation_id) WHERE relation_id IS NOT NULL;
