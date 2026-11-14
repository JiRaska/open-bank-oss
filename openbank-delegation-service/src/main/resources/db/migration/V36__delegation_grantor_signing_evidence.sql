-- Persist the verified issuer evidence at offer creation. Existing rows are not backfilled.
-- Rollback: ALTER TABLE delegation_grants DROP CONSTRAINT chk_delegation_grantor_evidence,
--   DROP COLUMN grantor_signing_actor_id, DROP COLUMN grantor_sole_mandate_id;
ALTER TABLE delegation_grants
    ADD COLUMN grantor_signing_actor_id UUID,
    ADD COLUMN grantor_sole_mandate_id UUID,
    ADD CONSTRAINT chk_delegation_grantor_evidence CHECK (
        (grantor_signing_actor_id IS NULL AND grantor_sole_mandate_id IS NULL)
        OR (
            grantor_signing_actor_id IS NOT NULL
            AND ((grantor_signing_actor_id = grantor_party_id) = (grantor_sole_mandate_id IS NULL))
        )
    );
