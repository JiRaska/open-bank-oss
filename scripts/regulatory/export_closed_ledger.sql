-- SPDX-License-Identifier: Apache-2.0
-- Run with psql -XqAt -v ON_ERROR_STOP=1 -v as_of=YYYY-MM-DD on an isolated,
-- immutable copy of the reconciled ledger close. Standard output is NDJSON.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH entries AS (
    SELECT j.*, count(*) OVER (PARTITION BY j.id) AS id_count
    FROM journal_entries j
    WHERE j.entry_date <= :'as_of'::date
        AND j.status IN ('POSTED', 'REVERSED')
        AND NOT j.synthetic
), lines AS (
    SELECT j.entry_date, j.id AS journal_id, l.id AS line_id,
        CASE WHEN j.id_count = 1 THEN j.booking_context_version_id END AS context_id,
        j.booking_company_id, j.booking_mode, j.booking_branch_id,
        g.code, g.type, l.base_currency,
        CASE WHEN l.side IN ('D', 'C') AND l.base_amount > 0
            AND trunc(l.base_amount * 100) = l.base_amount * 100
            AND l.base_amount * 100 <= 1000000000000000000
            THEN (l.base_amount * CASE WHEN l.side = 'D' THEN 100 ELSE -100 END)::bigint
        END AS net_cents
    FROM entries j
    LEFT JOIN journal_lines l ON l.journal_id = j.id
    LEFT JOIN gl_accounts g ON g.id = l.gl_account_id
)
SELECT json_build_object(
    'sequence', row_number() OVER (ORDER BY entry_date, journal_id, line_id),
    'contextId', context_id,
    'bookingCompanyId', booking_company_id,
    'bookingMode', booking_mode,
    'bookingBranchId', booking_branch_id,
    'code', code,
    'accountType', type,
    'currency', base_currency,
    'netCents', net_cents
)
FROM lines
ORDER BY entry_date, journal_id, line_id;

COMMIT;
