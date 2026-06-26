CREATE TABLE tpp_entries (
    id          BIGSERIAL PRIMARY KEY,
    tpp_id      VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    country_code CHAR(2) NOT NULL,
    nca         VARCHAR(20) NOT NULL,
    roles       VARCHAR(100) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    qwac_subject_dn  TEXT,
    qseal_subject_dn TEXT,
    qwac_expires_at  DATE,
    qseal_expires_at DATE,
    registered_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    blacklisted_at   TIMESTAMPTZ,
    blacklist_reason TEXT
);

CREATE INDEX idx_tpp_entries_status ON tpp_entries(status);
CREATE INDEX idx_tpp_entries_country ON tpp_entries(country_code);

CREATE TABLE eba_sync_state (
    id              BIGSERIAL PRIMARY KEY,
    last_sync_at    TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    total_entries   INT NOT NULL DEFAULT 0,
    error_message   TEXT
);

INSERT INTO tpp_entries (tpp_id, name, country_code, nca, roles, status, registered_at, updated_at)
VALUES
    ('CZ-CNB-SANDBOX-001', 'OpenBank Sandbox TPP', 'CZ', 'CNB', 'AISP,PISP', 'ACTIVE', NOW(), NOW()),
    ('CZ-CNB-TEST-AISP', 'Test AISP', 'CZ', 'CNB', 'AISP', 'ACTIVE', NOW(), NOW()),
    ('CZ-CNB-TEST-PISP', 'Test PISP', 'CZ', 'CNB', 'PISP', 'ACTIVE', NOW(), NOW());
