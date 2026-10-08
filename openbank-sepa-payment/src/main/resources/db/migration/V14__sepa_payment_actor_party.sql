-- The edge authenticates a natural person independently of the effective party when acting
-- for an entity. Existing rows remain unverifiable for actor-specific receipt lookup.
-- Rollback after reverting application code: DROP COLUMN initiating_actor_party_id.
ALTER TABLE sepa_payments ADD COLUMN initiating_actor_party_id UUID;
