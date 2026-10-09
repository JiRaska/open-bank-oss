-- Existing rows cannot prove their creator or original payload; leave their bindings null.
ALTER TABLE sct_inst_payments
    ADD COLUMN request_hash VARCHAR(64),
    ADD COLUMN initiating_principal VARCHAR(255),
    ADD COLUMN initiating_party_id UUID,
    ADD COLUMN initiating_actor_party_id UUID;
