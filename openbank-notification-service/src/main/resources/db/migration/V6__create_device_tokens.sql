-- Device token registry (push fan-out target). One human/party may have many push-capable
-- devices; a PUSH notification fans out to every ACTIVE token for the party. The token is
-- provider-issued (FCM registration token / APNs device token) and PII-adjacent — masked
-- before logging (PiiMask, openbank-libs), never returned in full over REST.
CREATE TABLE device_tokens (
    id            BIGSERIAL PRIMARY KEY,
    device_id     UUID NOT NULL UNIQUE,
    party_id      UUID NOT NULL,
    app_instance  VARCHAR(255) NOT NULL,
    platform      VARCHAR(10) NOT NULL,
    token         TEXT NOT NULL,
    app_version   VARCHAR(40),
    os_version    VARCHAR(40),
    status        VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    last_used_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One row per (platform, token): re-registration of the same token upserts (refreshes the
-- party binding + status) instead of accumulating duplicates.
CREATE UNIQUE INDEX idx_device_tokens_platform_token ON device_tokens(platform, token);
-- Fan-out lookup path: active tokens for a party.
CREATE INDEX idx_device_tokens_party_active ON device_tokens(party_id, status);

-- Hibernate Reactive + Kotlin PanacheEntity allocate the id from a sequence named
-- "<table>_seq" (default allocationSize 50); BIGSERIAL alone only yields "<table>_id_seq".
-- Without this every INSERT would fail with: relation "device_tokens_seq" does not exist.
-- Same convention as V5__notification_sequences.sql; enforced by HibernateSequenceGuardTest.
-- Rollback: DROP TABLE device_tokens; DROP SEQUENCE device_tokens_seq;
CREATE SEQUENCE IF NOT EXISTS device_tokens_seq INCREMENT BY 50;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
