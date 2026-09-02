-- Reusable delegation role presets. Existing grants copy capabilities and are never changed by preset edits.
-- Rollback: revert application images; the additive tables are ignored by older binaries. If permanent
-- removal is required, drop them in a new forward Flyway migration (never edit this applied checksum).
CREATE TABLE delegation_role_presets (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    resource_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE delegation_role_preset_capabilities (
    preset_id UUID NOT NULL REFERENCES delegation_role_presets(id) ON DELETE CASCADE,
    capability VARCHAR(100) NOT NULL,
    PRIMARY KEY (preset_id, capability)
);

CREATE INDEX idx_delegation_role_presets_name ON delegation_role_presets(name);

INSERT INTO delegation_role_presets (id, name, description, resource_type, created_at, updated_at) VALUES
('00000000-0000-0000-0000-000000000101', 'Účetní', 'Vidí zůstatky a transakce, bez možnosti odeslat peníze.', 'ACCOUNT', NOW(), NOW()),
('00000000-0000-0000-0000-000000000102', 'Pokladník', 'Připravuje platby ke schválení, ale sám je nevykoná.', 'ACCOUNT', NOW(), NOW()),
('00000000-0000-0000-0000-000000000103', 'Držitel dodatkové karty', 'Vidí kartu a spravuje její provozní limity.', 'CARD', NOW(), NOW());

INSERT INTO delegation_role_preset_capabilities (preset_id, capability) VALUES
('00000000-0000-0000-0000-000000000101', 'ACCOUNT_READ_BALANCES'),
('00000000-0000-0000-0000-000000000101', 'ACCOUNT_READ_TRANSACTIONS'),
('00000000-0000-0000-0000-000000000102', 'ACCOUNT_READ_BALANCES'),
('00000000-0000-0000-0000-000000000102', 'ACCOUNT_PROPOSE_PAYMENT'),
('00000000-0000-0000-0000-000000000103', 'CARD_VIEW'),
('00000000-0000-0000-0000-000000000103', 'CARD_MANAGE_LIMITS');

GRANT ALL ON delegation_role_presets, delegation_role_preset_capabilities TO openbank;
