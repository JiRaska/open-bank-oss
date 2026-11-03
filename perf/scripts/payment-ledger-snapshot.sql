-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Read-only repeatable-read snapshots for verify-payment-ledger.py.
-- Transaction DB: set run_id and run_prefix; omit -v ledger.
-- Ledger DB: additionally set ledger=true, started_at, ended_at, transaction_ids (UUID[] literal).

\set ON_ERROR_STOP on
\set QUIET on

\if :{?ledger}
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
WITH run_entries AS (
  SELECT id, entry_date, transaction_id, status, reversal_of, created_at, synthetic
  FROM journal_entries
  WHERE (created_at >= :'started_at'::timestamptz AND created_at <= :'ended_at'::timestamptz)
     OR transaction_id = ANY(:'transaction_ids'::uuid[])
), orphan_lines AS (
  SELECT jl.id, jl.journal_id
  FROM journal_lines jl
  WHERE NOT EXISTS (SELECT 1 FROM journal_entries je WHERE je.id = jl.journal_id)
)
SELECT jsonb_build_object(
  'schema_version', 1,
  'run_id', :'run_id',
  'profile_id', 'cz-current-account-5m-v1',
  'profile_version', 1,
  'source', 'ledger-service',
  'captured_at', clock_timestamp(),
  'journal_entries', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'id', e.id, 'entry_date', e.entry_date, 'transaction_id', e.transaction_id,
      'status', e.status, 'reversal_of', e.reversal_of, 'created_at', e.created_at,
      'synthetic', e.synthetic
    ) ORDER BY e.id, e.entry_date) FROM run_entries e
  ), '[]'::jsonb),
  'journal_lines', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'id', jl.id, 'journal_id', jl.journal_id, 'side', jl.side,
      'amount', jl.amount::text, 'currency_code', jl.currency_code,
      'base_amount', jl.base_amount::text, 'base_currency', jl.base_currency,
      'gl_account_id', jl.gl_account_id, 'sub_account_id', jl.sub_account_id
    ) ORDER BY jl.id)
    FROM journal_lines jl JOIN run_entries e ON e.id = jl.journal_id
  ), '[]'::jsonb),
  'gl_accounts', coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'id', ga.id, 'code', ga.code, 'currency_code', ga.currency_code, 'is_enabled', ga.is_enabled
    ) ORDER BY ga.id) FROM gl_accounts ga
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
  'schema_version', 1,
  'run_id', :'run_id',
  'profile_id', 'cz-current-account-5m-v1',
  'profile_version', 1,
  'source', 'transaction-service',
  'captured_at', clock_timestamp(),
  'rows', coalesce(jsonb_agg(jsonb_build_object(
    'id', t.id, 'booking_date', t.booking_date, 'idempotency_key', t.idempotency_key,
    'type', t.type, 'status', t.status, 'amount', t.amount::text,
    'currency_code', t.currency_code, 'source_account_id', t.source_account_id,
    'target_account_id', t.target_account_id, 'reversal_of', t.reversal_of
  ) ORDER BY t.idempotency_key, t.booking_date), '[]'::jsonb)
)
FROM transactions t
WHERE left(t.idempotency_key, length(:'run_prefix')) = :'run_prefix';
COMMIT;
\endif
