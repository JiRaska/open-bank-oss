-- ADR-0380. Preserve the exact operation before the remote catalogue fence.
-- Rollback: first stop passport writers and drain or reconcile all intents. Keep the
-- table while any passport account can still activate or receipt recovery is required.
CREATE TABLE account_passport_intents (
    operation_key TEXT PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    account_id UUID NOT NULL,
    grant_id UUID NOT NULL,
    party_id UUID NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('OPEN', 'ACTIVATE')),
    binding TEXT NOT NULL CHECK (binding ~ '^[a-f0-9]{64}$'),
    pin JSONB NOT NULL,
    UNIQUE (operation, account_id)
);
