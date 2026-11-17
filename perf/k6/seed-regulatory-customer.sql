-- SPDX-License-Identifier: Apache-2.0
-- Run against the freshly migrated, isolated ob_perf_* databases.
-- Example: psql -X -v clients=3000000 -d ob_perf_accounts -f seed-regulatory-customer.sql
\set ON_ERROR_STOP on
BEGIN;
SELECT 1 / ((
    current_database() IN ('ob_perf_accounts', 'ob_perf_balances', 'ob_perf_domestic', 'ob_perf_parties')
    AND :'clients'::integer BETWEEN 1 AND 3000000
)::integer) AS isolated_fixture_target;

SELECT current_database() = 'ob_perf_accounts' AS account_db,
       current_database() = 'ob_perf_balances' AS balance_db,
       current_database() = 'ob_perf_parties' AS party_db
\gset

CREATE TEMP VIEW fixture_clients AS
SELECT n,
       md5('regulatory-perf/party/' || n)::uuid AS party_id,
       md5('regulatory-perf/account/' || n)::uuid AS account_id,
       md5('regulatory-perf/payment/' || n)::uuid AS payment_id,
       '000000' || lpad(n::text, 10, '0') AS local_number
FROM generate_series(1, :'clients'::integer) AS n;

\if :account_db
INSERT INTO accounts (id, account_number, account_type, party_id, product_id, currency_code)
SELECT account_id,
       'CZ' || lpad((98 - mod(('0800' || local_number || '123500')::numeric, 97))::text, 2, '0')
           || '0800' || local_number,
       'CURRENT', party_id, md5('regulatory-perf/product')::uuid, 'CZK'
FROM fixture_clients
ON CONFLICT (id) DO NOTHING;
ANALYZE accounts;
\elif :balance_db
INSERT INTO balances (account_id, currency, booked_amount, available_amount)
SELECT account_id, 'CZK', 1000 + n % 10000, 1000 + n % 10000
FROM fixture_clients
ON CONFLICT (account_id, currency) DO NOTHING;
ANALYZE balances;
\elif :party_db
INSERT INTO parties (party_id, party_type, status, legal_name, email, kyc_status)
SELECT party_id, 'INDIVIDUAL', 'ACTIVE', 'Synthetic customer',
       'fixture-' || n || '@example.invalid', 'APPROVED'
FROM fixture_clients
ON CONFLICT (party_id) DO NOTHING;
ANALYZE parties;
\else
INSERT INTO domestic_payments (
    payment_id, idempotency_key, status, debtor_account_id, debtor_account_number, debtor_bank_code,
    debtor_name, creditor_account_number, creditor_bank_code, creditor_name,
    amount, currency, priority, end_to_end_id, settled_at
)
SELECT payment_id, 'regulatory-perf/' || n, 'SETTLED', account_id, local_number, '0800',
       'Synthetic debtor', '0000000000000001', '0100', 'Synthetic creditor',
       1 + n % 1000, 'CZK', 'STANDARD', 'regulatory-perf/' || n, now()
FROM fixture_clients
ON CONFLICT (payment_id) DO NOTHING;
ANALYZE domestic_payments;
\endif
COMMIT;
