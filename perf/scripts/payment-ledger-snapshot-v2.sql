-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Additive schema-v2 snapshots for verify-payment-ledger-v2.py. V1 stays unchanged.
-- Transaction DB: set run_id and run_prefix; omit -v ledger.
-- Ledger DB: set ledger=true, started_at, ended_at, and transaction_ids UUID[] from ALL v2 claims/rows.

\set ON_ERROR_STOP on
\set QUIET on

\if :{?ledger}
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
WITH run_ids AS (
  SELECT DISTINCT transaction_id
  FROM unnest(:'transaction_ids'::uuid[]) AS ids(transaction_id)
), expected_keys AS (
  SELECT 'workflow-' || transaction_id::text || '-ledger' AS idempotency_key
  FROM run_ids
), expected_idempotency AS (
  SELECT li.idempotency_key, li.journal_id, li.journal_entry_date, li.created_at
  FROM ledger_idempotency li
  WHERE li.idempotency_key IN (SELECT idempotency_key FROM expected_keys)
), seed_journal_ids AS (
  -- Include the expected pointer IDs and every header directly linked to a run transaction.
  -- This lets the snapshot expose unexpected alias keys without recursively expanding pointers.
  SELECT journal_id FROM expected_idempotency
  UNION
  SELECT je.id FROM journal_entries je
  WHERE je.transaction_id IN (SELECT transaction_id FROM run_ids)
), run_idempotency AS (
  SELECT li.idempotency_key, li.journal_id, li.journal_entry_date, li.created_at
  FROM ledger_idempotency li
  WHERE li.idempotency_key IN (SELECT idempotency_key FROM expected_keys)
     OR li.journal_id IN (SELECT journal_id FROM seed_journal_ids)
), causal_entries AS (
  SELECT je.id, je.entry_date, je.transaction_id, je.status, je.reversal_of,
         je.created_at, je.synthetic
  FROM journal_entries je
  WHERE je.transaction_id IN (SELECT transaction_id FROM run_ids)
     OR EXISTS (
       SELECT 1 FROM run_idempotency li
       WHERE li.journal_id = je.id
     )
), causal_journal_ids AS (
  SELECT id AS journal_id FROM causal_entries
  UNION
  SELECT journal_id FROM run_idempotency
), window_entries AS (
  -- Keep the original independent run-window census. It is not used to select causal entries.
  SELECT je.id, je.entry_date, je.transaction_id, je.status, je.reversal_of,
         je.created_at, je.synthetic
  FROM journal_entries je
  WHERE je.created_at >= :'started_at'::timestamptz
    AND je.created_at <= :'ended_at'::timestamptz
), causal_lines AS (
  SELECT jl.id, jl.journal_id, jl.side, jl.amount, jl.currency_code,
         jl.base_amount, jl.base_currency, jl.gl_account_id, jl.sub_account_id
  FROM journal_lines jl
  WHERE jl.journal_id IN (SELECT journal_id FROM causal_journal_ids)
), window_lines AS (
  SELECT jl.id, jl.journal_id, jl.side, jl.amount, jl.currency_code,
         jl.base_amount, jl.base_currency, jl.gl_account_id, jl.sub_account_id
  FROM journal_lines jl
  WHERE EXISTS (SELECT 1 FROM window_entries we WHERE we.id = jl.journal_id)
), orphan_lines AS (
  -- Preserve the V1 whole-table anti-join; attribution is reported separately by the verifier.
  SELECT jl.id, jl.journal_id
  FROM journal_lines jl
  WHERE NOT EXISTS (SELECT 1 FROM journal_entries je WHERE je.id = jl.journal_id)
)
SELECT jsonb_build_object(
  'schema_version', 2,
  'run_id', :'run_id',
  'profile_id', 'cz-current-account-5m-v1',
  'profile_version', 1,
  'source', 'ledger-service',
  'captured_at', clock_timestamp(),
  'ledger_idempotency', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'idempotency_key', li.idempotency_key, 'journal_id', li.journal_id,
      'journal_entry_date', li.journal_entry_date, 'created_at', li.created_at
    ) ORDER BY li.idempotency_key) FROM run_idempotency li
  ), '[]'::jsonb),
  'journal_entries', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'id', e.id, 'entry_date', e.entry_date, 'transaction_id', e.transaction_id,
      'status', e.status, 'reversal_of', e.reversal_of, 'created_at', e.created_at,
      'synthetic', e.synthetic
    ) ORDER BY e.id, e.entry_date) FROM causal_entries e
  ), '[]'::jsonb),
  'journal_lines', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'id', l.id, 'journal_id', l.journal_id, 'side', l.side,
      'amount', l.amount::text, 'currency_code', l.currency_code,
      'base_amount', l.base_amount::text, 'base_currency', l.base_currency,
      'gl_account_id', l.gl_account_id, 'sub_account_id', l.sub_account_id
    ) ORDER BY l.id) FROM causal_lines l
  ), '[]'::jsonb),
  'gl_accounts', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'id', ga.id, 'code', ga.code, 'currency_code', ga.currency_code, 'is_enabled', ga.is_enabled
    ) ORDER BY ga.id) FROM gl_accounts ga
  ), '[]'::jsonb),
  'window_journal_entries', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'id', e.id, 'entry_date', e.entry_date, 'transaction_id', e.transaction_id,
      'status', e.status, 'reversal_of', e.reversal_of, 'created_at', e.created_at,
      'synthetic', e.synthetic
    ) ORDER BY e.created_at, e.id, e.entry_date) FROM window_entries e
  ), '[]'::jsonb),
  'window_journal_lines', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'id', l.id, 'journal_id', l.journal_id, 'side', l.side,
      'amount', l.amount::text, 'currency_code', l.currency_code,
      'base_amount', l.base_amount::text, 'base_currency', l.base_currency,
      'gl_account_id', l.gl_account_id, 'sub_account_id', l.sub_account_id
    ) ORDER BY l.id) FROM window_lines l
  ), '[]'::jsonb),
  'orphan_journal_lines', coalesce((
    SELECT jsonb_agg(jsonb_build_object('id', ol.id, 'journal_id', ol.journal_id) ORDER BY ol.id)
    FROM orphan_lines ol
  ), '[]'::jsonb)
);
COMMIT;
\else
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
  'schema_version', 2,
  'run_id', :'run_id',
  'profile_id', 'cz-current-account-5m-v1',
  'profile_version', 1,
  'source', 'transaction-service',
  'captured_at', clock_timestamp(),
  'claims', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'idempotency_key', c.idempotency_key, 'transaction_id', c.transaction_id,
      'booking_date', c.booking_date
    ) ORDER BY c.idempotency_key)
    FROM transaction_idempotency_keys c
    WHERE left(c.idempotency_key, length(:'run_prefix')) = :'run_prefix'
  ), '[]'::jsonb),
  'rows', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'id', t.id, 'booking_date', t.booking_date, 'idempotency_key', t.idempotency_key,
      'type', t.type, 'status', t.status, 'amount', t.amount::text,
      'currency_code', t.currency_code, 'source_account_id', t.source_account_id,
      'target_account_id', t.target_account_id, 'reversal_of', t.reversal_of
    ) ORDER BY t.idempotency_key, t.booking_date)
    FROM transactions t
    WHERE left(t.idempotency_key, length(:'run_prefix')) = :'run_prefix'
       OR t.id IN (
         SELECT c.transaction_id FROM transaction_idempotency_keys c
         WHERE left(c.idempotency_key, length(:'run_prefix')) = :'run_prefix'
       )
  ), '[]'::jsonb)
);
COMMIT;
\endif
