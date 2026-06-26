CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE parties (
    id                              UUID            NOT NULL,
    party_type                      VARCHAR(30)     NOT NULL,
    status                          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',

    given_name                      VARCHAR(100)    NOT NULL,
    family_name                     VARCHAR(100)    NOT NULL,
    birthdate                       DATE            NOT NULL,
    birth_number_encrypted          VARCHAR(512),
    gender                          VARCHAR(10),
    birthplace                      VARCHAR(200),
    nationalities                   TEXT[]          NOT NULL DEFAULT '{}',

    verification_source             VARCHAR(30)     NOT NULL,
    verified_at                     TIMESTAMPTZ     NOT NULL,

    email                           VARCHAR(255),
    email_verified_at               TIMESTAMPTZ,
    phone                           VARCHAR(30),
    phone_verified_at               TIMESTAMPTZ,
    preferred_language              VARCHAR(5)      NOT NULL DEFAULT 'cs',
    data_box_id                     VARCHAR(20),

    kyc_level                       VARCHAR(20)     NOT NULL DEFAULT 'NONE',
    kyc_completed_at                TIMESTAMPTZ,
    kyc_expires_at                  TIMESTAMPTZ,
    aml_risk_score                  VARCHAR(20)     NOT NULL DEFAULT 'LOW',
    pep_flag                        BOOLEAN         NOT NULL DEFAULT FALSE,
    sanctions_flag                  BOOLEAN         NOT NULL DEFAULT FALSE,
    ubo_verified_at                 TIMESTAMPTZ,
    last_aml_review_at              TIMESTAMPTZ,

    permanent_address_street        VARCHAR(200),
    permanent_address_house_number  VARCHAR(20),
    permanent_address_city          VARCHAR(100),
    permanent_address_postal_code   VARCHAR(10),
    permanent_address_country       VARCHAR(3),
    permanent_address_ruian_code    VARCHAR(20),
    rob_synced_at                   TIMESTAMPTZ,

    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_parties PRIMARY KEY (id),
    CONSTRAINT chk_party_type CHECK (party_type IN ('NATURAL_PERSON','LEGAL_ENTITY','SOLE_TRADER')),
    CONSTRAINT chk_party_status CHECK (status IN ('ACTIVE','SUSPENDED','DECEASED','TERMINATED')),
    CONSTRAINT chk_kyc_level CHECK (kyc_level IN ('NONE','BASIC','ENHANCED','FULL')),
    CONSTRAINT chk_aml_risk CHECK (aml_risk_score IN ('LOW','MEDIUM','HIGH','UNACCEPTABLE'))
);

CREATE TABLE party_external_ids (
    id          BIGSERIAL       NOT NULL,
    party_id    UUID            NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
    id_type     VARCHAR(30)     NOT NULL,
    id_value    VARCHAR(255)    NOT NULL,
    verified_at TIMESTAMPTZ,

    CONSTRAINT pk_party_external_ids PRIMARY KEY (id),
    CONSTRAINT uq_party_external_id UNIQUE (id_type, id_value),
    CONSTRAINT chk_id_type CHECK (id_type IN (
        'KEYCLOAK_ID','BANKID_SUB','ROB_AIFO','ICO','PASSPORT_NUMBER','ID_CARD_NUMBER'
    ))
);

CREATE TABLE party_id_documents (
    id              BIGSERIAL       NOT NULL,
    party_id        UUID            NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
    doc_type        VARCHAR(30)     NOT NULL,
    doc_number      VARCHAR(50)     NOT NULL,
    issuing_country VARCHAR(3)      NOT NULL,
    issued_at       DATE,
    expires_at      DATE,

    CONSTRAINT pk_party_id_documents PRIMARY KEY (id),
    CONSTRAINT chk_doc_type CHECK (doc_type IN (
        'NATIONAL_ID','PASSPORT','DRIVING_LICENSE','RESIDENCE_PERMIT'
    ))
);

CREATE TABLE party_relationships (
    id                  UUID            NOT NULL,
    party_id            UUID            NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
    role                VARCHAR(30)     NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    onboarded_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    onboarding_channel  VARCHAR(20)     NOT NULL,
    terminated_at       TIMESTAMPTZ,
    termination_reason  VARCHAR(500),

    CONSTRAINT pk_party_relationships PRIMARY KEY (id),
    CONSTRAINT chk_rel_role CHECK (role IN (
        'CUSTOMER','EMPLOYEE','ADMIN','AGENT','GUARANTOR','AUTHORIZED_PERSON'
    )),
    CONSTRAINT chk_rel_status CHECK (status IN ('ACTIVE','SUSPENDED','TERMINATED')),
    CONSTRAINT uq_active_role UNIQUE (party_id, role, status)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_parties_family_name ON parties (family_name);
CREATE INDEX idx_parties_birthdate   ON parties (birthdate);
CREATE INDEX idx_parties_email       ON parties (email);
CREATE INDEX idx_parties_status      ON parties (status);
CREATE INDEX idx_parties_kyc_level   ON parties (kyc_level);
CREATE INDEX idx_parties_pep         ON parties (pep_flag) WHERE pep_flag = TRUE;
CREATE INDEX idx_parties_sanctions   ON parties (sanctions_flag) WHERE sanctions_flag = TRUE;
CREATE INDEX idx_ext_ids_party       ON party_external_ids (party_id);
CREATE INDEX idx_ext_ids_lookup      ON party_external_ids (id_type, id_value);
CREATE INDEX idx_relationships_party ON party_relationships (party_id);
CREATE INDEX idx_relationships_role  ON party_relationships (role, status);
