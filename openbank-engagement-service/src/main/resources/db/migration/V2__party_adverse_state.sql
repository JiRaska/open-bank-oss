-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0220 D3.5 — the vulnerable-customer targeting exclusion, materialised for real (was always
-- constructed empty until now, see ResolveSurfaceUseCase). Surrogate id + unique (party_id,
-- state) business key, same convention as party-service's party_marketing_consent (V16):
-- an app-assigned @Id makes persist() INSERT-only in Hibernate Reactive, so a natural-key PK
-- would 500 on every re-set of an already-active state. Rollback: DROP TABLE party_adverse_state;

CREATE TABLE party_adverse_state (
    id       UUID         NOT NULL PRIMARY KEY,
    party_id UUID         NOT NULL,
    state    VARCHAR(32)  NOT NULL,
    set_at   TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX ux_party_adverse_state_party_state ON party_adverse_state (party_id, state);
