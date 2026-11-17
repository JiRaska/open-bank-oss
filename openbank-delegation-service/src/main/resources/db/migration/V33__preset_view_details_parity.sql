-- ADR-0375 D7. The `Účetní` and `Pokladník` presets grant ACCOUNT_READ_BALANCES without
-- ACCOUNT_VIEW_DETAILS. That was invisible while the account-detail route asked for the BALANCES
-- capability: both roles could drill into the account record on an authority that does not name it.
-- The route now asks for ACCOUNT_VIEW_DETAILS, so without this the two presets would mint grants
-- that can read a balance and not see which account it belongs to — a role nobody can use.
--
-- Scope: the PRESET catalog only. Grants already minted are immutable snapshots by design (V7
-- says so) and are deliberately NOT rewritten here: a grant carries what the grantor agreed to at
-- the time, and widening one after the fact would be the delegation service editing somebody's
-- mandate. An existing Účetní/Pokladník grantee keeps balances and transactions and loses the
-- account-detail drill-down until the grantor re-shares from the corrected preset.
--
-- Rollback: delete the two rows added here in a new forward migration. Never edit this file after
-- it has been applied.
INSERT INTO delegation_role_preset_capabilities (preset_id, capability) VALUES
('00000000-0000-0000-0000-000000000101', 'ACCOUNT_VIEW_DETAILS'),
('00000000-0000-0000-0000-000000000102', 'ACCOUNT_VIEW_DETAILS')
ON CONFLICT DO NOTHING;
