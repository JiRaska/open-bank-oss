-- Customer channel controls (#1): which rails a card may transact on. Default all-on so every
-- existing card keeps working exactly as before this migration.
ALTER TABLE cards ADD COLUMN contactless_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE cards ADD COLUMN online_enabled      BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE cards ADD COLUMN atm_enabled         BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE cards ADD COLUMN abroad_enabled      BOOLEAN NOT NULL DEFAULT TRUE;
