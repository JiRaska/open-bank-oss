-- Transactional idempotency for account opening (#465 concurrency sweep).
--
-- The Redis idempotency record in the REST layer is a check-then-act cache: two concurrent
-- opens with the same Idempotency-Key both miss it and BOTH open an account (two IBANs, two
-- AccountCreated events, one of them orphaned when the second Redis save overwrites the first).
-- This table makes the key part of the same DB transaction as the account + primary pocket
-- insert (ledger_idempotency pattern): the loser dies on the primary key and recovers by
-- returning the winner's account.
--
-- Rollback: DROP TABLE account_idempotency;
CREATE TABLE account_idempotency (
    idempotency_key     VARCHAR(255) NOT NULL,
    account_id          UUID         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_account_idempotency PRIMARY KEY (idempotency_key)
);

CREATE INDEX idx_account_idempotency_account ON account_idempotency(account_id);
