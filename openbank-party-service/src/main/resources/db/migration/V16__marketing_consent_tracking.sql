-- Marketing consent projection tracking (ADR-0205 D4).
--
-- Backs the party-service consumer of consent-service's outbox: keys the currently ACTIVE
-- marketing consent per party by consentId, so a ConsentRevoked/ConsentExpired event only
-- clears `parties.consent_marketing` when it matches the CURRENTLY tracked consent — an
-- out-of-order or late-delivered revoke for a consent a customer has since re-granted must
-- not incorrectly flip a fresh grant back to false. One row per party (a party can have at
-- most one live marketing consent aggregate, per ADR-0205 D2's single-aggregate decision).
CREATE TABLE party_marketing_consent (
    id          BIGSERIAL PRIMARY KEY,
    party_id    UUID        NOT NULL UNIQUE,
    consent_id  UUID        NOT NULL,
    granted_at  TIMESTAMPTZ NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS "party_marketing_consent_SEQ" INCREMENT BY 50;

-- Rollback note: DROP TABLE party_marketing_consent; DROP SEQUENCE "party_marketing_consent_SEQ";
-- Safe to drop — this table is a projection-tracking aid, not a source of truth; consent-service
-- remains authoritative and the table can be rebuilt from its outbox if ever lost.

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
