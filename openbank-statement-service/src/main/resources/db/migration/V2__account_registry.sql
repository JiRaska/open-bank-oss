-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0035 follow-up: local account registry for the scheduled monthly period-close.
--
-- The scheduler must enumerate every account to close, but account-service exposes no
-- "all accounts" endpoint (its listing requires a partyId) and owns its own DB (ADR-0002,
-- no cross-service DB reads). Instead statement-service builds a read-only projection by
-- consuming the account-service `AccountCreated` event stream
-- (topic openbank.accounts.account.created) into this registry. Enumeration then reads it
-- locally — decoupled from account-service availability, eventually consistent (fine for a
-- monthly batch). The consumer replays from earliest on first deploy to backfill history.
--
-- Rollback: DROP TABLE account_registry;

CREATE TABLE account_registry (
    account_id      UUID            PRIMARY KEY,
    party_id        UUID            NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    registered_at   TIMESTAMPTZ     NOT NULL DEFAULT now()
);
