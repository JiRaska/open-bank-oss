// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.persistence.repository

import com.openbank.aml.application.port.out.SuspiciousTransactionReportRepository
import com.openbank.aml.domain.report.OutcomeLabel
import com.openbank.aml.domain.report.ReportState
import com.openbank.aml.domain.report.ReportedMovement
import com.openbank.aml.domain.report.SuspiciousTransactionReport
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.instant
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.mutate
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.requiredInstant
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.select
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.utc
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class SuspiciousTransactionReportRepositoryImpl(private val outbox: AmlOutboxRepositoryImpl) : SuspiciousTransactionReportRepository {

    override suspend fun insert(report: SuspiciousTransactionReport, event: OutboxMessage) {
        Panache.withTransaction {
            var chain: Uni<Int> = mutate(
                """
                INSERT INTO aml_str (report_id, party_id, state, narrative, drafted_by, drafted_at, updated_at)
                VALUES (:id, :partyId, :state, :narrative, :draftedBy, :draftedAt, :draftedAt)
                """,
                mapOf(
                    "id" to report.id,
                    "partyId" to report.partyId,
                    "state" to report.state.name,
                    "narrative" to report.narrative,
                    "draftedBy" to report.draftedBy,
                    "draftedAt" to utc(report.draftedAt),
                ),
            )
            report.caseIds.forEach { caseId ->
                chain = chain.chain { _ ->
                    mutate(
                        "INSERT INTO aml_str_case (report_id, case_id) VALUES (:id, :caseId)",
                        mapOf("id" to report.id, "caseId" to caseId),
                    )
                }
            }
            report.movements.forEach { movement ->
                chain = chain.chain { _ ->
                    mutate(
                        """
                        INSERT INTO aml_str_movement (report_id, journal_entry_id, account_id, currency)
                        VALUES (:id, :journal, :account, :currency)
                        """,
                        mapOf(
                            "id" to report.id,
                            "journal" to movement.journalEntryId,
                            "account" to movement.accountId,
                            "currency" to movement.currency,
                        ),
                    )
                }
            }
            chain.chain { _ -> outbox.persistInTransaction(event) }
        }.awaitSuspending()
    }

    override suspend fun find(reportId: UUID): SuspiciousTransactionReport? {
        val row = Panache.withSession { select("$SELECT WHERE report_id = :id", mapOf("id" to reportId)) }
            .awaitSuspending().firstOrNull() ?: return null
        return hydrate(row)
    }

    override suspend fun list(state: ReportState?, limit: Int): List<SuspiciousTransactionReport> {
        val rows = Panache.withSession {
            if (state == null) {
                select("$SELECT ORDER BY updated_at DESC LIMIT :limit", mapOf("limit" to limit))
            } else {
                select(
                    "$SELECT WHERE state = :state ORDER BY updated_at DESC LIMIT :limit",
                    mapOf("state" to state.name, "limit" to limit),
                )
            }
        }.awaitSuspending()
        return rows.map { hydrate(it) }
    }

    // The WHERE on the previous state is the guard: two officers acting at once cannot both win.
    override suspend fun update(report: SuspiciousTransactionReport, from: ReportState, event: OutboxMessage): Boolean =
        Panache.withTransaction {
            mutate(
                """
                UPDATE aml_str
                   SET state = :state,
                       approved_by = nullif(:approvedBy, ''), approved_at = cast(nullif(:approvedAt, '') as timestamptz),
                       filing_reference = nullif(:reference, ''), filed_at = cast(nullif(:filedAt, '') as timestamptz),
                       filing_recorded_by = nullif(:recordedBy, ''),
                       filing_recorded_at = cast(nullif(:recordedAt, '') as timestamptz),
                       withdrawn_by = nullif(:withdrawnBy, ''), withdrawn_at = cast(nullif(:withdrawnAt, '') as timestamptz),
                       outcome_vocabulary = nullif(:vocabulary, ''),
                       outcome_revision = cast(nullif(:revision, '') as integer),
                       outcome_hash = nullif(:hash, ''), outcome_code = nullif(:code, ''),
                       updated_at = :updatedAt
                 WHERE report_id = :id AND state = :from
                """,
                mapOf(
                    "state" to report.state.name,
                    "approvedBy" to (report.approvedBy ?: ""),
                    "approvedAt" to (report.approvedAt?.toString() ?: ""),
                    "reference" to (report.filingReference ?: ""),
                    "filedAt" to (report.filedAt?.toString() ?: ""),
                    "recordedBy" to (report.filingRecordedBy ?: ""),
                    "recordedAt" to (report.filingRecordedAt?.toString() ?: ""),
                    "withdrawnBy" to (report.withdrawnBy ?: ""),
                    "withdrawnAt" to (report.withdrawnAt?.toString() ?: ""),
                    "vocabulary" to (report.outcome?.vocabularyId ?: ""),
                    "revision" to (report.outcome?.vocabularyRevision?.toString() ?: ""),
                    "hash" to (report.outcome?.vocabularyHash ?: ""),
                    "code" to (report.outcome?.code ?: ""),
                    "updatedAt" to utc(report.updatedAt),
                    "id" to report.id,
                    "from" to from.name,
                ),
            ).chain { updated ->
                if (updated == 1) outbox.persistInTransaction(event).replaceWith(true) else Uni.createFrom().item(false)
            }
        }.awaitSuspending()

    override suspend fun countMonitoringCases(partyId: UUID, caseIds: List<UUID>): Long {
        var found = 0L
        for (caseId in caseIds) {
            found += Panache.withSession {
                select(
                    """
                    SELECT case_id, party_id FROM aml_cases
                     WHERE case_id = :caseId AND party_id = :partyId AND screening_type = 'TRANSACTION_MONITORING'
                       AND idempotency_key LIKE 'tm:%'
                    """,
                    mapOf("caseId" to caseId, "partyId" to partyId),
                )
            }.awaitSuspending().size
        }
        return found
    }

    override suspend fun countPartyMovements(partyId: UUID, report: SuspiciousTransactionReport): Long {
        var found = 0L
        for (movement in report.movements) {
            found += Panache.withSession {
                select(
                    """
                    SELECT journal_entry_id, party_id FROM aml_booked_movement
                     WHERE journal_entry_id = :journal AND account_id = :account AND currency = :currency
                       AND party_id = :partyId
                    """,
                    mapOf(
                        "journal" to movement.journalEntryId,
                        "account" to movement.accountId,
                        "currency" to movement.currency,
                        "partyId" to partyId,
                    ),
                )
            }.awaitSuspending().size
        }
        return found
    }

    override suspend fun oldestApprovedNotFiled(): Instant? = Panache.withSession {
        select("SELECT count(*), min(approved_at) FROM aml_str WHERE state = 'APPROVED'", emptyMap())
    }.awaitSuspending().firstOrNull()?.let { instant(it[1]) }

    private suspend fun hydrate(row: Array<Any?>): SuspiciousTransactionReport {
        val id = row[ID] as UUID
        val cases = Panache.withSession {
            select("SELECT case_id, report_id FROM aml_str_case WHERE report_id = :id ORDER BY case_id", mapOf("id" to id))
        }.awaitSuspending().map { it[0] as UUID }
        val movements = Panache.withSession {
            select(
                """
                SELECT journal_entry_id, account_id, currency FROM aml_str_movement
                 WHERE report_id = :id ORDER BY journal_entry_id, account_id, currency
                """,
                mapOf("id" to id),
            )
        }.awaitSuspending().map { ReportedMovement(it[0] as UUID, it[1] as UUID, it[2] as String) }
        return SuspiciousTransactionReport(
            id = id,
            partyId = row[PARTY] as UUID,
            state = ReportState.valueOf(row[STATE] as String),
            narrative = row[NARRATIVE] as String,
            caseIds = cases,
            movements = movements,
            draftedBy = row[DRAFTED_BY] as String,
            draftedAt = requiredInstant(row[DRAFTED_AT]),
            approvedBy = row[APPROVED_BY] as? String,
            approvedAt = instant(row[APPROVED_AT]),
            filingReference = row[REFERENCE] as? String,
            filedAt = instant(row[FILED_AT]),
            filingRecordedBy = row[RECORDED_BY] as? String,
            filingRecordedAt = instant(row[RECORDED_AT]),
            withdrawnBy = row[WITHDRAWN_BY] as? String,
            withdrawnAt = instant(row[WITHDRAWN_AT]),
            outcome = (row[CODE] as? String)?.let {
                OutcomeLabel(row[VOCABULARY] as String, (row[REVISION] as Number).toInt(), row[HASH] as String, it)
            },
            updatedAt = requiredInstant(row[UPDATED_AT]),
        )
    }

    private companion object {
        const val SELECT = """
            SELECT report_id, party_id, state, narrative, drafted_by, drafted_at, approved_by, approved_at,
                   filing_reference, filed_at, filing_recorded_by, filing_recorded_at, withdrawn_by, withdrawn_at,
                   outcome_vocabulary, outcome_revision, outcome_hash, outcome_code, updated_at
              FROM aml_str"""
        const val ID = 0
        const val PARTY = 1
        const val STATE = 2
        const val NARRATIVE = 3
        const val DRAFTED_BY = 4
        const val DRAFTED_AT = 5
        const val APPROVED_BY = 6
        const val APPROVED_AT = 7
        const val REFERENCE = 8
        const val FILED_AT = 9
        const val RECORDED_BY = 10
        const val RECORDED_AT = 11
        const val WITHDRAWN_BY = 12
        const val WITHDRAWN_AT = 13
        const val VOCABULARY = 14
        const val REVISION = 15
        const val HASH = 16
        const val CODE = 17
        const val UPDATED_AT = 18
    }
}
