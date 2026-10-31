-- Read fixtures for the ledger read-baseline lane (.github/workflows/perf-gate.yml).
--
-- The chart of accounts is seeded by Flyway (V1), so this file only adds journals:
--   * one POSTED journal at a FIXED id (00000000-0000-0000-0000-0000000000e1) probed by the
--     journal-detail read, and
--   * 199 more POSTED journals spread over the last 90 days so the journal list pages real
--     rows; every journal is double-entry on chart accounts 1001 (Nostro, DEBIT) and 2001
--     (Current Account Deposits, CREDIT) with sub_account_id = the fleet-wide fixture
--     account, so the per-customer sub-ledger-balance read sums a real position.
--
-- journal_entries is PARTITION BY RANGE(entry_date); inserts through the parent route into
-- the 2026 partition. entry_number comes from the sequence default; (entry_number,
-- entry_date) stays unique because every row gets its own sequence value. The generated
-- journal ids are deterministic (hex of g, zero-padded) so the line insert needs no join.
-- synthetic stays FALSE — these are perf fixtures, not synthetic-journey money.

-- Fixed detail-probe journal first (PK (id, entry_date) makes a re-load a no-op-ish duplicate
-- key on the same day; the gate loads fixtures exactly once into a throwaway database).
INSERT INTO journal_entries (id, transaction_id, entry_date, value_date, description, status, created_by)
VALUES ('00000000-0000-0000-0000-0000000000e1',
        '00000000-0000-0000-0000-0000000000f1',
        CURRENT_DATE, CURRENT_DATE,
        'PERF JOURNAL DETAIL FIXTURE', 'POSTED',
        '00000000-0000-0000-0000-0000000000d1');

INSERT INTO journal_lines (journal_id, gl_account_id, side, amount, currency_code, base_amount, base_currency, sequence, sub_account_id)
SELECT '00000000-0000-0000-0000-0000000000e1', id,
       CASE WHEN code = '1001' THEN 'D' ELSE 'C' END,
       1000.00, 'CZK', 1000.00, 'CZK',
       CASE WHEN code = '1001' THEN 1 ELSE 2 END,
       CASE WHEN code = '2001' THEN '00000000-0000-0000-0000-0000000000c2'::uuid ELSE NULL END
FROM gl_accounts WHERE code IN ('1001', '2001');

-- The remaining 199 journals.
INSERT INTO journal_entries (id, transaction_id, entry_date, value_date, description, status, created_by)
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
       gen_random_uuid(),
       CURRENT_DATE - (g % 90), CURRENT_DATE - (g % 90),
       'PERF JOURNAL ' || g, 'POSTED',
       '00000000-0000-0000-0000-0000000000d1'
FROM generate_series(1, 199) AS g;

-- Two lines per journal (double-entry): debit Nostro 1001, credit customer deposit 2001
-- tagged with the fixture sub-account.
INSERT INTO journal_lines (journal_id, gl_account_id, side, amount, currency_code, base_amount, base_currency, sequence, sub_account_id)
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
       a.id,
       CASE WHEN a.code = '1001' THEN 'D' ELSE 'C' END,
       100 + (g % 500), 'CZK', 100 + (g % 500), 'CZK',
       CASE WHEN a.code = '1001' THEN 1 ELSE 2 END,
       CASE WHEN a.code = '2001' THEN '00000000-0000-0000-0000-0000000000c2'::uuid ELSE NULL END
FROM generate_series(1, 199) AS g
CROSS JOIN gl_accounts a
WHERE a.code IN ('1001', '2001');
