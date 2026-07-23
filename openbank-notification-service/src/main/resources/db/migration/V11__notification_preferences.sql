-- Per-party push preferences (#2). A missing row means "all on", so no backfill is needed and
-- existing customers keep receiving every push until they opt out. Security-critical categories
-- (OTP / SCA / KYC / account freeze) are never gated by these flags.
CREATE TABLE notification_preferences (
    id             BIGSERIAL PRIMARY KEY,
    party_id       UUID        NOT NULL UNIQUE,
    payments_push  BOOLEAN     NOT NULL DEFAULT TRUE,
    product_push   BOOLEAN     NOT NULL DEFAULT TRUE,
    marketing_push BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Hibernate Reactive + Kotlin PanacheEntity allocate the id from a sequence named
-- "<table>_seq" (default allocationSize 50); BIGSERIAL alone only yields "<table>_id_seq".
-- Enforced by HibernateSequenceGuardTest. Rollback: DROP TABLE notification_preferences;
-- DROP SEQUENCE notification_preferences_seq;
CREATE SEQUENCE IF NOT EXISTS notification_preferences_seq INCREMENT BY 50;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
