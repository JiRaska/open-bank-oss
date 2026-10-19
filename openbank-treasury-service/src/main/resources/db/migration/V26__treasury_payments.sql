-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D6 / contract section 5: own-account payments proposed from FIXED flows inside the cut-off
-- window (netted per netting agreement), released by a human payment releaser who is neither the
-- deal's creator nor a term author, then handed to the rail of the counterparty's SSI. Incoming
-- payments are expected receipts that nostro matching settles.
--
-- Rollback: UPDATE deal_cash_flows SET payment_id = NULL; DROP TABLE treasury_payment_flows,
-- treasury_payments.

CREATE TABLE treasury_payments (
    payment_id         UUID          PRIMARY KEY,
    status             VARCHAR(24)   NOT NULL CHECK (status IN ('PROPOSED', 'RELEASED', 'SENT', 'SETTLED', 'FAILED',
                                                               'CANCELLED', 'RAIL_NOT_SUPPORTED', 'MANUALLY_SETTLED',
                                                               'RETURNED')),
    direction          VARCHAR(3)    NOT NULL CHECK (direction IN ('IN', 'OUT')),
    counterparty_id    VARCHAR(64)   NOT NULL,
    currency           CHAR(3)       NOT NULL,
    amount             NUMERIC(20,2) NOT NULL CHECK (amount > 0),
    value_date         DATE          NOT NULL,
    rail               VARCHAR(16),
    beneficiary_account VARCHAR(64),
    bic                VARCHAR(11),
    ssi_version        INT,
    deal_ids           TEXT          NOT NULL,
    term_authors       TEXT          NOT NULL,
    idempotency_key    VARCHAR(128)  NOT NULL UNIQUE,
    created_at         TIMESTAMPTZ   NOT NULL,
    rail_reference     VARCHAR(128),
    released_by        VARCHAR(255),
    released_at        TIMESTAMPTZ,
    status_reason      VARCHAR(500),
    CONSTRAINT treasury_payments_out_addressed CHECK (direction = 'IN' OR rail IS NOT NULL),
    CONSTRAINT treasury_payments_released_by_human CHECK (released_by IS NULL OR released_by NOT LIKE 'agent:%')
);
CREATE INDEX idx_treasury_payments_value ON treasury_payments (value_date, status);

-- A flow belongs to at most one live payment: the link row exists only while its payment is not
-- CANCELLED (a cancellation deletes it), the flow is the primary key, and the SLOT (deal, leg,
-- period, kind) is unique too, so a regenerated schedule's new row for an already-paid slot can
-- never be linked to a second payment.
CREATE TABLE treasury_payment_flows (
    flow_id      UUID        PRIMARY KEY REFERENCES deal_cash_flows (flow_id),
    payment_id   UUID        NOT NULL REFERENCES treasury_payments (payment_id),
    deal_id      UUID        NOT NULL,
    leg_index    INT         NOT NULL,
    period_index INT         NOT NULL,
    kind         VARCHAR(20) NOT NULL,
    CONSTRAINT treasury_payment_flows_slot UNIQUE (deal_id, leg_index, period_index, kind)
);
CREATE INDEX idx_treasury_payment_flows_payment ON treasury_payment_flows (payment_id);
