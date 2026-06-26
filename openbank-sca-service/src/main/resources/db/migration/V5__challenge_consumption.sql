-- Single-use SCA challenges (RTS Art. 5 replay protection): an approved challenge is
-- spent atomically on the operation it authorised; a second consume attempt fails the
-- compare-and-consume UPDATE (consumed_at IS NULL guard) instead of replaying.
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMPTZ;
