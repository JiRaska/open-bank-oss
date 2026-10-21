-- Customer opt-in for one account pocket. A missing row never sends an alert.
CREATE TABLE balance_low_alert_preferences (
    id               BIGSERIAL PRIMARY KEY,
    account_id       UUID           NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    party_id         UUID           NOT NULL,
    enabled          BOOLEAN        NOT NULL DEFAULT TRUE,
    threshold        NUMERIC(23, 4) NOT NULL CHECK (threshold >= 0),
    rearm_margin     NUMERIC(23, 4) NOT NULL CHECK (rearm_margin > 0),
    armed            BOOLEAN        NOT NULL,
    generation       BIGINT         NOT NULL DEFAULT 0 CHECK (generation >= 0),
    last_alert_at    TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT balance_low_alert_preferences_pocket_key UNIQUE (account_id, currency)
);

CREATE SEQUENCE IF NOT EXISTS balance_low_alert_preferences_seq INCREMENT BY 50;
CREATE INDEX balance_low_alert_preferences_party_idx
    ON balance_low_alert_preferences (party_id) WHERE enabled;

GRANT ALL ON balance_low_alert_preferences TO openbank;
GRANT ALL ON SEQUENCE balance_low_alert_preferences_seq TO openbank;

-- Rollback after first disabling the low-balance consumer and draining notification intents:
-- DROP TABLE balance_low_alert_preferences;
-- DROP SEQUENCE balance_low_alert_preferences_seq;
