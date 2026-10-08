-- Bind future payment receipts to the authenticated creator. Existing rows remain unverifiable.
-- The party id is accepted only from the customer-edge service principal, whose edge handler
-- resolves it from the customer JWT and checks debtor account ownership before forwarding.
-- Rollback after reverting application code: DROP both columns. This discards receipt provenance.
ALTER TABLE sepa_payments ADD COLUMN initiating_principal VARCHAR(255);
ALTER TABLE sepa_payments ADD COLUMN initiating_party_id UUID;
