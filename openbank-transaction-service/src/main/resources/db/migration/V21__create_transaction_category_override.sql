-- Rollback: DROP TABLE transaction_category_override; never edit an applied Flyway migration.
-- Safe to drop: the table is additive and read-only to the rest of the service. Losing it loses
-- the customers' own categories — irrecoverable, since nothing else records them — but the read
-- path falls back to merchantCategory and the merchant catalogue, so statements still render.
--
-- A customer's own categorisation of their spending, keyed by WHO they paid rather than by which
-- payment.
--
-- Why not per transaction: categorising one payment teaches the app nothing about the next one.
-- The landlord is paid every month; naming that counterparty once has to hold for every future
-- payment to them, or the customer is re-categorising the same rent twelve times a year.
--
-- Why this reaches further than the merchant catalogue: the catalogue is keyed by a CARD ACQUIRER
-- descriptor, so it can only ever categorise card spend. Rent, transfers between people and direct
-- debits carry no descriptor and stayed uncategorised no matter how good the catalogue got. Here
-- the key is derived from the ISO 20022 counterparty name when there is one and falls back to the
-- descriptor, so both kinds of spending land in the same mechanism.
--
-- Scope is the ACCOUNT, not the customer. The read path is account-scoped, and an account is what
-- both its owner and any delegate see; a per-party table would need a party resolution this
-- service does not have and would give two people looking at one statement two different answers.
--
-- Precedence is settled in the read path: an override here WINS over the catalogue category. The
-- customer looking at their own statement is the better authority on what their money went on.
CREATE TABLE transaction_category_override (
    account_id       UUID        NOT NULL,
    -- Produced by MerchantDescriptor.normalise(), the same function the merchant catalogue keys on,
    -- so an override on a shop and its catalogue entry agree on what that shop is called.
    counterparty_key VARCHAR(120) NOT NULL,
    -- One of SpendCategory.IDS. Not a foreign key and not an enum: the vocabulary lives in code and
    -- is shared with card-issuance, and a category retired there must not make these rows
    -- unreadable. The read path treats an unrecognised value as no override.
    category         VARCHAR(40) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, counterparty_key)
);

-- The read path looks up every counterparty on one page of transactions for one account, so the
-- primary key's leading column is already the right index. No second index is added.

COMMENT ON TABLE transaction_category_override IS
    'Customer-set spend category per (account, counterparty). Overrides merchant_catalog.category.';
COMMENT ON COLUMN transaction_category_override.counterparty_key IS
    'MerchantDescriptor.normalise() of the ISO 20022 counterparty name, or of the acquirer descriptor when there is no name';
