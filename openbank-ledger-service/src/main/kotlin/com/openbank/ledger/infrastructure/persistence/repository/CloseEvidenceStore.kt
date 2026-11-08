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
        )
    }

    /** Never reconstructed from later reports; commits or rolls back with the day and its outbox. */
    fun capture(record: AccountingDayRecord, requireTieOut: Boolean): Uni<Unit> {
        if (record.status != AccountingDayStatus.TIED_OUT) return Uni.createFrom().item(Unit)
        return Panache.getSession().flatMap { session ->
            JournalReadWatermark.lockCapture(session).flatMap {
                session.createNativeMutationQuery(CAPTURE_SQL)
                    .setParameter("d", record.businessDate)
                    .setParameter("at", clock.instant())
                    .setParameter("mode", participantsConfig.participantsMode())
                    .setParameter(
                        "expected",
                        objectMapper.writeValueAsString(
                            participantsConfig.participants().mapValues {
                                it.value.principal()
                            },
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
                            "and the latest completed participant work"
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
        const val CAPTURE_SQL = "INSERT INTO ledger_close_evidence " +
            "(business_date, observed_at, mode, expected_principals, expected_jobs, tie_out_run_id, " +
            "reports, journal_count) " +
            "VALUES (:d, :at, :mode, CAST(CAST(:expected AS text) AS jsonb), " +
            "CAST(CAST(:expectedJobs AS text) AS jsonb), " +
            "(SELECT t.id FROM (SELECT id, status, run_at, started_at, journal_count, " +
            "accounts_checked, breaks, errors " +
            "FROM ledger_tieout_runs WHERE as_of = :d " +
            "ORDER BY run_at DESC, id DESC LIMIT 1) t WHERE t.status = 'OK' AND t.accounts_checked > 0 " +
            "AND t.breaks = 0 AND t.errors = 0 AND t.run_at <= :at AND t.started_at >= " +
            "GREATEST((SELECT cutoff_at FROM ledger_accounting_day WHERE business_date = :d), " +
            "(SELECT max(reported_at) FROM ledger_close_participation WHERE business_date = :d AND outcome = 'RAN' " +
            "AND jsonb_exists(CAST(CAST(:expected AS text) AS jsonb), participant))) " +
            "AND t.journal_count = (SELECT count(*) FROM journal_entries WHERE entry_date <= :d)), " +
            "(SELECT COALESCE(jsonb_agg(jsonb_build_object('participant', p.participant, " +
            "'outcome', p.outcome, 'reportedBy', p.reported_by, 'reason', p.reason, " +
            "'exercisedJobs', p.exercised_jobs)), '[]'::jsonb) " +
            "FROM (SELECT DISTINCT ON (participant) participant, outcome, reported_by, reason, exercised_jobs " +
            "FROM ledger_close_participation WHERE business_date = :d ORDER BY participant, seq DESC) p), " +
            "(SELECT count(*) FROM journal_entries WHERE entry_date <= :d))"

        const val READ_SQL = "SELECT business_date, observed_at, mode, " +
            "CAST(expected_principals AS text), CAST(reports AS text), CAST(expected_jobs AS text), tie_out_run_id, " +
            "COALESCE(e.journal_count = (SELECT count(*) FROM journal_entries j " +
            "WHERE j.entry_date <= e.business_date), false) FROM ledger_close_evidence e " +
            "WHERE business_date >= :fromDate AND business_date <= :toDate ORDER BY business_date"
    }
}
