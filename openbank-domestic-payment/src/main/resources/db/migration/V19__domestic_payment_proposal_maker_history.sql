-- Expand-only index for bounded, maker-scoped keyset history. The immutable V18 rows stay intact.
CREATE INDEX idx_domestic_payment_proposal_maker_history
    ON domestic_payment_proposal_drafts (maker_party_id, created_at DESC, proposal_id DESC);
