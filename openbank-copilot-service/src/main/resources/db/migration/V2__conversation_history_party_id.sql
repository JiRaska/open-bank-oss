-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

-- Store the erasure identity AT WRITE TIME (#3881, GDPR Art. 17 / ADR-0117).
--
-- WHY: `customer_id` is the OIDC `sub` (CopilotChatResource.customerSubject()). The PARTY_ERASED
-- event carries `partyId`. Measured against the deployed customers realm (35 users) those two are
-- equal for ZERO of them: 9 users carry a `party_id` attribute that differs from `sub`, and the
-- remaining 26 carry none whose `sub` is a real party id. The ADR-0069 `sub == partyId` invariant
-- holds for nobody, because WebAuthnKeycloakClient.ensureUser never sets the Keycloak user `id`,
-- so Keycloak mints a random UUID and `party_id` lives on as a separate attribute.
--
-- So a consumer that deletes on `customer_id = partyId` receives the event, deletes nothing, and
-- logs success — a GDPR control that reports coverage it does not have. Resolving the identity at
-- DELETE time cannot be made reliable (it would need a Keycloak lookup for a user that erasure has
-- just removed); resolving it at WRITE time can, because the JWT carries both.
--
-- Nullable on purpose: rows written before this migration have no party id and nothing can infer
-- one for them. They stay reachable by `customer_id` (the delete matches either column), and a
-- backfill is a separate decision, not a blocker.
--
-- Rollback:
--   DROP INDEX IF EXISTS idx_conversation_history_party;
--   ALTER TABLE conversation_history DROP COLUMN party_id;
ALTER TABLE conversation_history ADD COLUMN party_id VARCHAR(255);

CREATE INDEX idx_conversation_history_party ON conversation_history (party_id);
