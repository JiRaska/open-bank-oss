-- ADR-0224 D2 (review round 3): the token binding is the JWT ID (jti), not the SSO `sid` —
-- Keycloak token exchange mints a fresh session id per exchange, while jti is unique to the
-- exchanged token itself. The BFF binds jti after a successful exchange; the resolver looks
-- sessions up by it, so a token can never outlive its session row.
ALTER TABLE agent_session ADD COLUMN jti VARCHAR(255);
CREATE UNIQUE INDEX idx_agent_session_jti ON agent_session(jti) WHERE jti IS NOT NULL;
