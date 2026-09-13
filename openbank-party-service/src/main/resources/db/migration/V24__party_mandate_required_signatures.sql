-- ADR-0284 D3: preserve the exact statutory-representation quorum. Historical JOINT rows remain
-- NULL because the previous model discarded whether they meant 2, 3, or more; consumers must fail
-- closed rather than inventing authority. Historical SOLE is unambiguous and can be restored.
-- Rollback: ALTER TABLE party_mandates DROP COLUMN required_signatures;

ALTER TABLE party_mandates ADD COLUMN required_signatures INTEGER;

UPDATE party_mandates SET required_signatures = 1 WHERE authority = 'SOLE';

ALTER TABLE party_mandates ADD CONSTRAINT chk_party_mandate_signature_quorum CHECK (
    required_signatures IS NULL
    OR (authority = 'SOLE' AND required_signatures = 1)
    OR (authority = 'JOINT' AND required_signatures >= 2)
);
