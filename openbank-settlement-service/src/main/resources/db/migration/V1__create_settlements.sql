-- SPDX-License-Identifier: MPL-2.0
CREATE TABLE settlements (
    id UUID PRIMARY KEY,
    payer_account_id UUID NOT NULL,
    payee_account_id UUID NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
