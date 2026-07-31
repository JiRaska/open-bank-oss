-- SPDX-License-Identifier: Apache-2.0
-- Compliance pack four-eyes activation (ADR-0212 D4). Packs are runtime-activated, never
-- Flyway-delivered: this table is the *persistence of the activation workflow*, not of pack
-- content as configuration. A pack proposal carries the full payload JSON so the in-memory
-- CompliancePackRegistry can be rehydrated at boot (compile-at-activation, ADR-0218 D3).
-- The partial unique index lets a REJECTED/WITHDRAWN version be re-proposed while activated
-- history stays immutable.
--
-- Rollback: DROP TABLE compliance_pack_activation;

CREATE TABLE compliance_pack_activation (
    id              uuid         PRIMARY KEY,
    state           VARCHAR(16)  NOT NULL,
    jurisdiction    VARCHAR(8)   NOT NULL,
    product_type    VARCHAR(32)  NOT NULL,
    pack_version    INT          NOT NULL,
    effective_from  DATE         NOT NULL,
    payload         TEXT         NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,
    proposed_by     VARCHAR(128) NOT NULL,
    proposed_at     TIMESTAMPTZ  NOT NULL,
    decided_by      VARCHAR(128),
    decided_at      TIMESTAMPTZ,
    decision_reason VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_pack_activation_live_version
    ON compliance_pack_activation (jurisdiction, product_type, pack_version)
    WHERE state IN ('PROPOSED', 'APPROVED', 'EXECUTED');

CREATE INDEX idx_pack_activation_state ON compliance_pack_activation(state);
