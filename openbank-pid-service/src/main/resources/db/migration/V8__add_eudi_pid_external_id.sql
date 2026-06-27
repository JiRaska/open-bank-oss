-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0094 — EUDI-native identity (eIDAS 2.0). Allow EUDI_PID_SUB as an external-id kind.
--
-- The value stored is the keyed blind index (HMAC-SHA256 hex, same pepper as BIRTH_NUMBER) of the
-- verified PID (Person Identification Data) subject identifier presented from an EUDI wallet — never
-- the plaintext government identifier. This is the tier-0 deterministic dedup key (strongest, above RČ):
-- it is set only AFTER cryptographic verification of the wallet's verifiable presentation.
-- Rollback: restore the prior CHECK (safe once no EUDI_PID_SUB rows exist) and drop the index.

ALTER TABLE party_external_ids DROP CONSTRAINT chk_id_type;

ALTER TABLE party_external_ids ADD CONSTRAINT chk_id_type CHECK (id_type IN (
    'KEYCLOAK_ID',
    'BANKID_SUB',
    'ROB_AIFO',
    'ICO',
    'PASSPORT_NUMBER',
    'ID_CARD_NUMBER',
    'BIRTH_NUMBER',
    'EUDI_PID_SUB'
));

CREATE INDEX idx_ext_ids_eudi_pid_sub
    ON party_external_ids (id_value)
    WHERE id_type = 'EUDI_PID_SUB';
