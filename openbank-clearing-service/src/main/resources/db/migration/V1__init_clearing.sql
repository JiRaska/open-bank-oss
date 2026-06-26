CREATE TYPE clearing_status AS ENUM ('PENDING', 'IN_CLEARING', 'SETTLED', 'FAILED', 'REVERSED');
CREATE TYPE settlement_type AS ENUM ('GROSS', 'NET', 'DEFERRED_NET');
CREATE TYPE payment_rail AS ENUM ('SEPA_SCT', 'SEPA_SCT_INST', 'SWIFT', 'DOMESTIC', 'INTERNAL');

CREATE TABLE clearing_batches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_reference VARCHAR(64) UNIQUE NOT NULL,
    rail            payment_rail NOT NULL,
    settlement_type settlement_type NOT NULL DEFAULT 'NET',
    status          clearing_status NOT NULL DEFAULT 'PENDING',
    total_debit     NUMERIC(20,4) NOT NULL DEFAULT 0,
    total_credit    NUMERIC(20,4) NOT NULL DEFAULT 0,
    net_position    NUMERIC(20,4) NOT NULL DEFAULT 0,
    currency        CHAR(3) NOT NULL DEFAULT 'EUR',
    item_count      INT NOT NULL DEFAULT 0,
    cycle_id        VARCHAR(32),
    settlement_date DATE,
    settled_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE clearing_items (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id            UUID NOT NULL REFERENCES clearing_batches(id),
    payment_id          UUID NOT NULL,
    payment_reference   VARCHAR(64) NOT NULL,
    debtor_iban         VARCHAR(34) NOT NULL,
    creditor_iban       VARCHAR(34) NOT NULL,
    debtor_bic          VARCHAR(11),
    creditor_bic        VARCHAR(11),
    amount              NUMERIC(20,4) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'EUR',
    status              clearing_status NOT NULL DEFAULT 'PENDING',
    value_date          DATE,
    end_to_end_id       VARCHAR(35),
    remittance_info     VARCHAR(140),
    error_code          VARCHAR(16),
    error_message       VARCHAR(256),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE settlement_positions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    participant_bic VARCHAR(11) NOT NULL,
    currency        CHAR(3) NOT NULL DEFAULT 'EUR',
    cycle_id        VARCHAR(32) NOT NULL,
    gross_debit     NUMERIC(20,4) NOT NULL DEFAULT 0,
    gross_credit    NUMERIC(20,4) NOT NULL DEFAULT 0,
    net_position    NUMERIC(20,4) NOT NULL DEFAULT 0,
    settled         BOOLEAN NOT NULL DEFAULT FALSE,
    settled_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(participant_bic, currency, cycle_id)
);

CREATE INDEX idx_clearing_items_batch ON clearing_items(batch_id);
CREATE INDEX idx_clearing_items_payment ON clearing_items(payment_id);
CREATE INDEX idx_clearing_items_status ON clearing_items(status);
CREATE INDEX idx_clearing_batches_status ON clearing_batches(status);
CREATE INDEX idx_clearing_batches_cycle ON clearing_batches(cycle_id);
CREATE INDEX idx_settlement_positions_cycle ON settlement_positions(cycle_id);
