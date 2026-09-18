-- Expand-only keyset index for the account owner's bounded proposal inbox.
-- V18's original owner index remains until a separately reviewed online contraction.
CREATE INDEX idx_domestic_payment_proposal_owner_inbox
    ON domestic_payment_proposal_drafts (owner_party_id, created_at DESC, proposal_id DESC);
