-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors.
--
-- ADR-0072 — Client identity unification.
--
-- Add BIRTH_NUMBER as a valid external-id kind in party_external_ids.
-- The value column holds the keyed blind index (HMAC-SHA256 hex), never
-- the plaintext RČ.  The existing UNIQUE(id_type, id_value) constraint on
-- party_external_ids is the dedup backstop: two concurrent onboardings with
-- the same RČ cannot both insert a BIRTH_NUMBER row — the loser catches a
-- constraint violation and is routed to manual verification, not to a
-- duplicate row.
--
-- Migration is backward-safe: it only widens the CHECK constraint and adds
-- an index.  No row is modified; an explicit backfill of existing parties
-- that carry birth_number_encrypted is a separate task (tracked as issue
-- #699 phase 2) because it requires access to the decryption key + pepper,
-- which must not flow through a Flyway migration.
--
-- Rollback note: to revert, drop the partial index and restore the original
-- CHECK constraint (see down-script below; execute manually if needed —
-- Flyway does not run down-scripts in the default open-source edition):
--
--   DROP INDEX IF EXISTS idx_ext_ids_birth_number;
--   ALTER TABLE party_external_ids DROP CONSTRAINT chk_id_type;
--   ALTER TABLE party_external_ids ADD CONSTRAINT chk_id_type
--       CHECK (id_type IN (
--           'KEYCLOAK_ID','BANKID_SUB','ROB_AIFO','ICO',
--           'PASSPORT_NUMBER','ID_CARD_NUMBER'
--       ));

-- 1. Drop the old constraint so we can replace it.
ALTER TABLE party_external_ids DROP CONSTRAINT chk_id_type;

-- 2. Recreate with BIRTH_NUMBER added.
ALTER TABLE party_external_ids ADD CONSTRAINT chk_id_type CHECK (id_type IN (
    'KEYCLOAK_ID',
    'BANKID_SUB',
    'ROB_AIFO',
    'ICO',
    'PASSPORT_NUMBER',
    'ID_CARD_NUMBER',
    'BIRTH_NUMBER'
));

-- 3. Partial index on the blind-index value column for fast equality lookup.
--    Scoped to BIRTH_NUMBER rows only so it stays tiny regardless of table growth.
CREATE INDEX idx_ext_ids_birth_number
    ON party_external_ids (id_value)
    WHERE id_type = 'BIRTH_NUMBER';
