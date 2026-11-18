-- SPDX-License-Identifier: Apache-2.0
-- V35: re-issue every Czech account number that fails the ČNB mod-11 checksum (#16748).
--
-- Why: accounts opened before IbanGenerator enforced CzechAccountNumber carry an IBAN that is
-- ISO 13616 mod-97 valid but whose BBAN no Czech bank could issue (prefix or base fails the
-- weighted mod-11 of ČNB Decree 169/2011 Sb. § 5). domestic-payment is about to reject such
-- numbers with a 400 at the API and a terminal AC01 at the scheme adapter, so they must be
-- replaced first, or the accounts that carry them stop being able to pay or be paid.
--
-- What it does, in the one Flyway transaction, per affected account:
--   1. derives a replacement deterministically from the old number: the bank code is kept, a
--      valid prefix is kept (an invalid one becomes the empty prefix 000000), and the base keeps
--      its first nine digits with the tenth solved for mod-11; where that is impossible (the
--      check digit would be 10, fewer than two significant digits, or the result is already
--      taken) the nine-digit head is stepped by one and the solve repeated;
--   2. recomputes the ISO 13616 mod-97-10 check digits;
--   3. updates accounts.account_number and bumps the optimistic-lock version;
--   4. records old -> new in account_number_reissue (the audit trail, append-only by intent);
--   5. writes an AccountNumberReissued outbox row, so projections keyed on the IBAN
--      (tax-reporting CEU register, statement, document) can follow. Every consumer of this
--      topic discriminates on the body eventType, so the new type is ignored where unhandled.
--
-- The account id, balances, pockets, authorizations and status are untouched; only the number
-- changes, so a demo/test account keeps working under its new, valid number.
--
-- Scope: only rows shaped exactly like a compact Czech IBAN (CZ + 22 digits). Anything else is
-- left alone. Idempotent: a second run finds nothing to re-issue.
--
-- Rollback (restores the old numbers; only meaningful before anything has been paid to a new one):
--   UPDATE accounts a SET account_number = r.previous_account_number, version = a.version + 1,
--          updated_at = now()
--     FROM account_number_reissue r WHERE r.account_id = a.id AND a.account_number = r.new_account_number;
--   DROP TABLE account_number_reissue;
-- The AccountNumberReissued outbox rows are left as history; once relayed they cannot be recalled,
-- so a rollback must publish compensating events by hand. Never edit this file once applied.

CREATE TABLE account_number_reissue (
    id                      BIGSERIAL    PRIMARY KEY,
    account_id              UUID         NOT NULL REFERENCES accounts (id),
    previous_account_number VARCHAR(34)  NOT NULL UNIQUE,
    new_account_number      VARCHAR(34)  NOT NULL UNIQUE,
    reason                  VARCHAR(64)  NOT NULL,
    reissued_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_number_reissue_account_id ON account_number_reissue (account_id);

-- Helpers live in pg_temp: they exist only for this migration's session and leave no trace.
CREATE FUNCTION pg_temp.cz_mod11(digits TEXT, weights INT[]) RETURNS INT
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    padded TEXT := lpad(digits, array_length(weights, 1), '0');
    total  INT  := 0;
BEGIN
    FOR i IN 1 .. array_length(weights, 1) LOOP
        total := total + substr(padded, i, 1)::INT * weights[i];
    END LOOP;
    RETURN total % 11;
END $$;

CREATE FUNCTION pg_temp.cz_valid_prefix(p TEXT) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE AS $$
    SELECT pg_temp.cz_mod11(p, ARRAY[10, 5, 8, 4, 2, 1]) = 0
$$;

CREATE FUNCTION pg_temp.cz_valid_base(b TEXT) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE AS $$
    SELECT pg_temp.cz_mod11(b, ARRAY[6, 3, 7, 9, 10, 5, 8, 4, 2, 1]) = 0
       AND length(replace(b, '0', '')) >= 2
$$;

-- ISO 13616: rearrange bban + "CZ00" (C=12, Z=35), check = 98 - (n mod 97).
CREATE FUNCTION pg_temp.cz_iban(bban TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE AS $$
    SELECT 'CZ' || lpad((98 - ((bban || '123500')::NUMERIC % 97))::INT::TEXT, 2, '0') || bban
$$;

DO $$
DECLARE
    acc        RECORD;
    bank_code  TEXT;
    prefix     TEXT;
    head       BIGINT;
    h9         TEXT;
    total      INT;
    check_d    INT;
    new_base   TEXT;
    new_iban   TEXT;
    weights    INT[] := ARRAY[6, 3, 7, 9, 10, 5, 8, 4, 2];
BEGIN
    FOR acc IN
        SELECT id, account_number, version
          FROM accounts
         WHERE account_number ~ '^CZ[0-9]{22}$'
           AND NOT (pg_temp.cz_valid_prefix(substr(account_number, 9, 6))
                AND pg_temp.cz_valid_base(substr(account_number, 15, 10)))
         ORDER BY id
    LOOP
        bank_code := substr(acc.account_number, 5, 4);
        prefix := substr(acc.account_number, 9, 6);
        IF NOT pg_temp.cz_valid_prefix(prefix) THEN
            prefix := '000000';
        END IF;

        IF pg_temp.cz_valid_base(substr(acc.account_number, 15, 10))
           AND NOT EXISTS (SELECT 1 FROM accounts
                            WHERE account_number = pg_temp.cz_iban(bank_code || prefix || substr(acc.account_number, 15, 10))) THEN
            -- Only the prefix was wrong: keep the base, drop the prefix.
            new_iban := pg_temp.cz_iban(bank_code || prefix || substr(acc.account_number, 15, 10));
        ELSE
            head := substr(acc.account_number, 15, 9)::BIGINT;
            LOOP
                h9 := lpad(head::TEXT, 9, '0');
                total := 0;
                FOR i IN 1 .. 9 LOOP
                    total := total + substr(h9, i, 1)::INT * weights[i];
                END LOOP;
                check_d := (11 - total % 11) % 11;
                IF check_d < 10 THEN
                    new_base := h9 || check_d::TEXT;
                    IF pg_temp.cz_valid_base(new_base) THEN
                        new_iban := pg_temp.cz_iban(bank_code || prefix || new_base);
                        EXIT WHEN NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = new_iban);
                    END IF;
                END IF;
                head := (head + 1) % 1000000000;
            END LOOP;
        END IF;

        UPDATE accounts
           SET account_number = new_iban, version = version + 1, updated_at = now()
         WHERE id = acc.id;

        INSERT INTO account_number_reissue (account_id, previous_account_number, new_account_number, reason)
        VALUES (acc.id, acc.account_number, new_iban, 'CZ_MOD11_INVALID');

        INSERT INTO account_outbox (event_id, aggregate_id, event_type, payload, status, created_at, updated_at)
        SELECT e.id, acc.id, 'AccountNumberReissued',
               json_build_object(
                   'eventId', e.id,
                   'aggregateId', acc.id,
                   'aggregateType', 'Account',
                   'eventType', 'AccountNumberReissued',
                   'version', acc.version + 1,
                   'previousAccountNumber', acc.account_number,
                   'accountNumber', new_iban,
                   'reason', 'CZ_MOD11_INVALID',
                   'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                   'sourceService', 'account-service'
               )::TEXT,
               'PENDING', now(), now()
          FROM (SELECT gen_random_uuid() AS id) e;
    END LOOP;
END $$;
