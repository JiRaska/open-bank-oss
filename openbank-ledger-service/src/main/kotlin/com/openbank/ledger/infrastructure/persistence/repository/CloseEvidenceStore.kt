// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.persistence.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.port.out.CloseEvidenceRepository
import com.openbank.ledger.application.usecase.CloseParticipantsConfig
import com.openbank.ledger.application.usecase.CloseReadinessConfig
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.ClosedDayParticipation
import com.openbank.ledger.domain.model.ClosedParticipantReport
import com.openbank.ledger.domain.model.checkConflict
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Read-only, bounded monthly query over snapshots committed with the day transition. */
@ApplicationScoped
class CloseEvidenceStore(
    private val objectMapper: ObjectMapper,
    private val participantsConfig: CloseParticipantsConfig,
    private val readinessConfig: CloseReadinessConfig,
    private val clock: Clock,
) : CloseEvidenceRepository {
    override suspend fun findMonth(from: LocalDate, to: LocalDate): List<ClosedDayParticipation> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Array<Any>>(READ_SQL)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .resultList
        }
    }.awaitSuspending().map { row ->
        ClosedDayParticipation(
            businessDate = LocalDate.parse(row[0].toString()),
            observedAt = when (val at = row[1]) {
                is Instant -> at
                is OffsetDateTime -> at.toInstant()
                else -> Instant.parse(at.toString())
            },
            mode = row[2].toString(),
            expectedPrincipals = objectMapper.readValue(
                row[EXPECTED_PRINCIPALS].toString(),
                object : TypeReference<Map<String, String>>() {},
            ),
            reports = objectMapper.readValue(
                row[REPORTS].toString(),
                Array<ClosedParticipantReport>::class.java,
            ).toList(),
            expectedJobs = objectMapper.readValue(
                row[EXPECTED_JOBS].toString(),
                object : TypeReference<Map<String, Set<String>>>() {},
            ),
            tieOutRunId = row[TIE_OUT_RUN]?.let { UUID.fromString(it.toString()) },
            postingsUnchanged = row[POSTINGS_UNCHANGED] == true,
            sourceEvidenceUnchanged = row[SOURCE_EVIDENCE_UNCHANGED] == true,
        )
    }

    /** Never reconstructed from later reports; commits or rolls back with the day and its outbox. */
    fun capture(record: AccountingDayRecord, requireTieOut: Boolean): Uni<Unit> {
        if (record.status != AccountingDayStatus.TIED_OUT) return Uni.createFrom().item(Unit)
        return Panache.getSession().flatMap { session ->
            // This read holds the parent relation's ACCESS SHARE lock until the surrounding
            // transition commits: archive DETACH/DROP cannot replace its source set underneath it.
            // Compute the fingerprint before excluding writers; only the short count check and
            // insert below hold the global exclusive lock. Concurrent inserts force a retry.
            JournalReadWatermark.readInTransaction(session, record.businessDate).flatMap { population ->
                JournalReadWatermark.lockCapture(session).flatMap {
                    session.createNativeQuery<Long>(JournalReadWatermark.COUNT_SQL)
                        .setParameter("d", record.businessDate).singleResult
                }.invoke { count ->
                    checkConflict(count == population.count) {
                        "Journal population changed during close; retry reconciliation"
                    }
                }.flatMap {
                    session.createNativeMutationQuery(CAPTURE_SQL)
                        .setParameter("d", record.businessDate)
                        .setParameter("at", clock.instant())
                        .setParameter("mode", participantsConfig.participantsMode())
                        .setParameter("fingerprint", population.fingerprint)
                        .setParameter("journalCount", population.count)
                        .setParameter(
                            "expected",
                            objectMapper.writeValueAsString(
                                participantsConfig.participants().mapValues { it.value.principal() },
                            ),
                        )
                        .setParameter("expectedJobs", objectMapper.writeValueAsString(readinessConfig.requiredJobs()))
                        .executeUpdate()
                }.flatMap {
                    if (!requireTieOut) return@flatMap Uni.createFrom().item(Unit)
                    session.createNativeQuery<Long>(
                        "SELECT count(*) FROM ledger_close_evidence " +
                            "WHERE business_date = :d AND tie_out_run_id IS NOT NULL",
                    ).setParameter("d", record.businessDate).singleResult.map { count ->
                        checkConflict(count == 1L) {
                            "Accounting day ${record.businessDate} needs a successful tie-out after cutoff " +
                                "and completed work over the same journal population"
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val EXPECTED_PRINCIPALS = 3
        const val REPORTS = 4
        const val EXPECTED_JOBS = 5
        const val TIE_OUT_RUN = 6
        const val POSTINGS_UNCHANGED = 7
        const val SOURCE_EVIDENCE_UNCHANGED = 8
        const val CAPTURE_SQL = "INSERT INTO ledger_close_evidence " +
            "(business_date, observed_at, mode, expected_principals, expected_jobs, tie_out_run_id, " +
            "reports, journal_count, journal_fingerprint) " +
            "VALUES (:d, :at, :mode, CAST(CAST(:expected AS text) AS jsonb), " +
            "CAST(CAST(:expectedJobs AS text) AS jsonb), " +
            "(SELECT t.id FROM (SELECT id, status, run_at, started_at, journal_count, journal_fingerprint, " +
            "accounts_checked, breaks, errors " +
            "FROM ledger_tieout_runs WHERE as_of = :d " +
            "ORDER BY run_at DESC, id DESC LIMIT 1) t WHERE t.status = 'OK' AND t.accounts_checked > 0 " +
            "AND t.breaks = 0 AND t.errors = 0 AND t.run_at <= :at AND t.started_at >= " +
            "GREATEST((SELECT cutoff_at FROM ledger_accounting_day WHERE business_date = :d), " +
            "(SELECT max(reported_at) FROM ledger_close_participation WHERE business_date = :d AND outcome = 'RAN' " +
            "AND jsonb_exists(CAST(CAST(:expected AS text) AS jsonb), participant))) " +
            "AND t.journal_count = :journalCount AND t.journal_fingerprint = :fingerprint), " +
            "(SELECT COALESCE(jsonb_agg(jsonb_build_object('participant', p.participant, " +
            "'outcome', p.outcome, 'reportedBy', p.reported_by, 'reason', p.reason, " +
            "'exercisedJobs', p.exercised_jobs, 'sourceRunId', p.source_run_id, " +
            "'receiptSeq', p.seq, 'reportedAt', p.reported_at)), '[]'::jsonb) " +
            "FROM (SELECT DISTINCT ON (participant) participant, outcome, reported_by, reason, exercised_jobs, " +
            "source_run_id, seq, reported_at " +
            "FROM ledger_close_participation WHERE business_date = :d ORDER BY participant, seq DESC) p), " +
            ":journalCount, :fingerprint)"

        val READ_SQL = "SELECT business_date, observed_at, mode, " +
            "CAST(expected_principals AS text), CAST(reports AS text), CAST(expected_jobs AS text), tie_out_run_id, " +
            "COALESCE(e.journal_fingerprint = (" + JournalReadWatermark.fingerprintQuery("e.business_date") +
            "), false), " +
            // Later source evidence can invalidate this close, never repair its frozen reports.
            // A legacy snapshot without receipt sequences cannot prove its source stayed unchanged.
            "NOT EXISTS (SELECT 1 FROM jsonb_object_keys(e.expected_principals) AS declared(participant) " +
            "WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements(e.reports) AS r(report) " +
            "WHERE r.report->>'participant' = declared.participant AND r.report->>'receiptSeq' IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM ledger_close_participation p " +
            "WHERE p.business_date = e.business_date AND p.participant = declared.participant " +
            "AND p.seq > CAST(r.report->>'receiptSeq' AS bigint)))) FROM ledger_close_evidence e " +
            "WHERE business_date >= :fromDate AND business_date <= :toDate ORDER BY business_date"
    }
}
