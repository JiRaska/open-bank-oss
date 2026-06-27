-- SPDX-License-Identifier: Apache-2.0
-- ADR-0021: decoupled device approval. Durable store of device credentials enrolled
-- to a party; the public key verifies later out-of-band approval assertions.
--
-- ROLLBACK: DROP TABLE IF EXISTS sca_enrolled_devices;  (no data dependency — credentials
-- are re-enrollable from the device; dropping forces re-enrollment, never silent bypass.)

CREATE TABLE sca_enrolled_devices (
    id              UUID         NOT NULL PRIMARY KEY,
    party_id        UUID         NOT NULL,
    credential_id   TEXT         NOT NULL UNIQUE,
    public_key_spki TEXT         NOT NULL,
    algorithm       VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_sca_enrolled_devices_party ON sca_enrolled_devices (party_id);
