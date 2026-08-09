-- D1: the single-use card lifecycle — a validity window, and why a card closed.
--
-- `status` is a VARCHAR, so the new CONSUMED value needs no type change; what was missing is the
-- ability to say WHY a card is dead. Until now "cancelled" covered a customer closing a card, a
-- lost card, and a disposable card doing exactly what it promised — three different things a
-- customer is owed three different sentences about.
ALTER TABLE cards ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE cards ADD COLUMN IF NOT EXISTS closed_reason VARCHAR(32);

COMMENT ON COLUMN cards.expires_at IS
    'When a SINGLE_USE card stops being usable even if never presented. NULL for other card types.';
COMMENT ON COLUMN cards.closed_reason IS
    'SINGLE_USE_CONSUMED | VALIDITY_EXPIRED | LOST_OR_STOLEN | CUSTOMER_CANCEL. NULL while the card is alive.';

-- Finding the disposable cards whose window has run out. Partial: only single-use cards ever carry
-- expires_at, so indexing the rest would be dead weight on the hot path.
CREATE INDEX IF NOT EXISTS idx_cards_expires_at
    ON cards (expires_at)
    WHERE expires_at IS NOT NULL;

-- Rollback note: dropping both columns is safe — nothing reads them for an ACTIVE card, and a card
-- in CONSUMED would need its status remapped to CANCELLED first, or it becomes a status no older
-- build understands.
