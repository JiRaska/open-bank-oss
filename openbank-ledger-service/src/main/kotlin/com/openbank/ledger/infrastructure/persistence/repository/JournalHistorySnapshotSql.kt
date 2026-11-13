// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

/** One SQL statement = one committed PostgreSQL MVCC view, not a max(sequence) watermark. */
internal object JournalHistorySnapshotSql {
    const val VALID_SOURCES = """
        NOT EXISTS (
            SELECT 1 FROM journal_history_snapshot_sources p
            WHERE p.snapshot_id = h.id AND NOT EXISTS (
                SELECT 1 FROM journal_entries j WHERE j.id = p.journal_id AND j.entry_date = p.entry_date
            )
        )
    """

    const val MANIFEST = """
        jsonb_build_object(
            'snapshotId', h.id, 'persisted', h.persisted, 'accountId', h.account_id, 'currency', h.currency,
            'fromDate', h.from_date, 'throughDate', h.through_date,
            'observedAt', h.observed_at, 'databaseView', h.database_view,
            'coverageScope', 'ATTACHED_LEDGER_VIEW_ONLY',
            'openingAnchorScope', 'SUM_OF_ATTACHED_BOOKED_LINES_BEFORE_FROM_DATE',
            'accountLifetimeComplete', false, 'classifiedHistoryComplete', false,
            'originTrust', 'AUTHENTICATED_CALLER_ASSERTION_UNVERIFIED',
            'openingBooked', h.opening_booked, 'closingBooked', h.closing_booked,
            'entryCount', h.entry_count, 'missingOriginCount', h.missing_origin_count,
            'recordsSha256', h.records_sha256, 'canonicalization', 'POSTGRES_JSONB_TEXT_LINES_V1'
        )
    """

    val CREATE = """
        WITH relevant AS MATERIALIZED (
            SELECT j.*, c.delta, c.customer_lines
            FROM journal_entries j
            CROSS JOIN LATERAL (
                SELECT sum(CASE WHEN l.side = 'C' THEN l.base_amount ELSE -l.base_amount END) AS delta,
                    jsonb_agg(jsonb_build_object(
                        'lineId', l.id, 'side', l.side, 'amount', l.base_amount,
                        'currency', l.base_currency, 'sequence', l.sequence
                    ) ORDER BY l.sequence, l.id) AS customer_lines
                FROM (
                    SELECT source.* FROM journal_lines source WHERE source.journal_id = j.id
                        AND source.sub_account_id = :accountId AND source.base_currency = :currency
                    ORDER BY source.id FOR SHARE
                ) l
            ) c
            WHERE j.status IN ('POSTED', 'REVERSED') AND j.entry_date <= :throughDate AND c.delta IS NOT NULL
            FOR SHARE OF j
        ), window_records AS MATERIALIZED (
            SELECT row_number() OVER (ORDER BY entry_date, created_at, id) AS ordinal,
                jsonb_build_object(
                    'journalId', id, 'entryDate', entry_date, 'valueDate', value_date,
                    'journalCreatedAt', created_at, 'transactionId', transaction_id,
                    'statusAtSnapshot', status, 'reversalOfJournalId', reversal_of,
                    'synthetic', synthetic, 'bookedDelta', delta, 'customerLines', customer_lines,
                    'origin', CASE WHEN origin_kind IS NULL THEN NULL ELSE jsonb_build_object(
                        'kind', origin_kind, 'sourceRecordId', origin_record_id,
                        'authenticatedSubmitter', origin_submitter, 'rail', origin_rail,
                        'instructionType', origin_instruction_type,
                        'reversalOfTransactionId', origin_reversal_of_transaction_id
                    ) END
                ) AS payload,
                origin_kind
            FROM relevant WHERE entry_date >= :fromDate
        ), candidate AS MATERIALIZED (
            SELECT cast(:snapshotId AS uuid) AS id, cast(:accountId AS uuid) AS account_id,
                cast(:currency AS varchar) AS currency, cast(:fromDate AS date) AS from_date,
                cast(:throughDate AS date) AS through_date,
                statement_timestamp() AS observed_at, pg_current_snapshot()::text AS database_view,
                coalesce((SELECT sum(delta) FROM relevant WHERE entry_date < :fromDate), 0) AS opening_booked,
                coalesce((SELECT sum(delta) FROM relevant), 0) AS closing_booked,
                count(*) AS entry_count, count(*) FILTER (WHERE origin_kind IS NULL) AS missing_origin_count,
                encode(sha256(convert_to(coalesce(string_agg(payload::text, E'\n' ORDER BY ordinal), ''), 'UTF8')), 'hex')
                    AS records_sha256,
                EXISTS (SELECT 1 FROM relevant) AS persisted
            FROM window_records
        ), header AS (
            INSERT INTO journal_history_snapshots
                (id, account_id, currency, from_date, through_date, observed_at, database_view,
                 opening_booked, closing_booked, entry_count, missing_origin_count, records_sha256)
            SELECT id, account_id, currency, from_date, through_date, observed_at, database_view,
                opening_booked, closing_booked, entry_count, missing_origin_count, records_sha256
            FROM candidate WHERE persisted RETURNING *
        ), copied AS (
            INSERT INTO journal_history_snapshot_entries(snapshot_id, ordinal, canonical_json)
            SELECT h.id, r.ordinal, r.payload FROM header h CROSS JOIN window_records r RETURNING snapshot_id
        ), provenance AS (
            INSERT INTO journal_history_snapshot_sources(snapshot_id, journal_id, entry_date)
            SELECT h.id, r.id, r.entry_date FROM header h CROSS JOIN relevant r RETURNING snapshot_id
        ) SELECT ($MANIFEST)::text FROM candidate h
    """.trimIndent()

    val READ = "SELECT ($MANIFEST)::text FROM journal_history_snapshots h WHERE h.id = :snapshotId AND $VALID_SOURCES"

    val PAGE = """
        SELECT e.ordinal, e.canonical_json::text FROM journal_history_snapshot_entries e
        JOIN journal_history_snapshots h ON h.id = e.snapshot_id
        WHERE h.id = :snapshotId AND e.ordinal > :afterOrdinal AND $VALID_SOURCES
        ORDER BY e.ordinal LIMIT :pageSize
    """.trimIndent()

    // Called after actual source partition lifecycle changes; never removes a source/held journal.
    val PURGE_UNAVAILABLE = "DELETE FROM journal_history_snapshots h WHERE NOT ($VALID_SOURCES)"
}
