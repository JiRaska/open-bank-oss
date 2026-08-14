// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.ClosedPeriodRepository
import com.openbank.ledger.domain.model.AccountingPeriod
import com.openbank.ledger.domain.model.ClosedPeriodEvidenceState
import com.openbank.ledger.domain.model.ClosedPeriodRecord
import com.openbank.ledger.domain.model.ClosedPeriodStatus
import com.openbank.ledger.domain.model.PeriodTrialBalance
import com.openbank.ledger.domain.model.PeriodType
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.ledger.infrastructure.persistence.entity.ClosedPeriodEntity
import com.openbank.ledger.infrastructure.persistence.entity.ClosedPeriodTrialBalanceLineEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class PanacheClosedPeriodRepository(
    private val clock: Clock,
    private val outboxRepository: LedgerOutboxRepositoryImpl,
) : ClosedPeriodRepository,
    PanacheRepositoryBase<ClosedPeriodEntity, UUID> {

    override suspend fun findByPeriod(period: AccountingPeriod): ClosedPeriodRecord? = Panache.withSession {
        find("periodType = ?1 and periodFrom = ?2", period.type.name, period.from).firstResult()
    }.awaitSuspending()?.toDomain()

    /**
     * The NARROWEST frozen period containing [date]. A month, its quarter and its year can all be
     * frozen; ordering by span ascending means the refusal names the boundary that actually sealed
     * the date, not the widest one that happens to contain it — different boundaries have different
     * remedies, and an operator told "the year is frozen" looks in the wrong place.
     */
    override suspend fun findFrozenContaining(date: LocalDate): ClosedPeriodRecord? = Panache.withSession {
        find(
            "status = ?1 and periodFrom <= ?2 and periodTo >= ?2 order by (periodTo - periodFrom) asc",
            ClosedPeriodStatus.FROZEN.name,
            date,
        ).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findRange(from: LocalDate, to: LocalDate): List<ClosedPeriodRecord> = Panache.withSession {
        find("periodFrom <= ?2 and periodTo >= ?1 order by periodFrom asc, periodTo asc", from, to).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findFrozenLines(periodId: UUID): List<TrialBalanceLine> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "FROM ClosedPeriodTrialBalanceLineEntity " +
                    "WHERE periodId = :periodId ORDER BY code asc, currency asc, glAccountId asc",
                ClosedPeriodTrialBalanceLineEntity::class.java,
            ).setParameter("periodId", periodId).resultList
        }
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun saveDraft(record: ClosedPeriodRecord): ClosedPeriodRecord = Panache.withTransaction {
        find("periodType = ?1 and periodFrom = ?2", record.period.type.name, record.period.from)
            .firstResult()
            .flatMap { existing ->
                if (existing == null) {
                    persist(record.toEntity())
                } else {
                    // Refresh in place; the unique (period_type, period_from) row is stable.
                    existing.status = record.status.name
                    existing.evidenceState = record.evidenceState.name
                    existing.computedAt = record.computedAt
                    existing.totalDebits = record.totalDebits
                    existing.totalCredits = record.totalCredits
                    existing.accountCount = record.accountCount
                    existing.contentHash = record.contentHash
                    // The maker becomes whoever produced THIS snapshot, so the checker always
                    // reviews against the author of the snapshot being reviewed (four-eyes).
                    existing.draftedBy = record.draftedBy
                    existing.updatedAt = Instant.now(clock)
                    Uni.createFrom().item(existing)
                }
            }
    }.awaitSuspending().toDomain()

    /**
     * Status flip + outbox row in ONE transaction (transactional outbox, ADR-0003/0050): either the
     * period is sealed and `PeriodFrozen` is queued, or neither happens.
     */
    override suspend fun saveFrozen(
        record: ClosedPeriodRecord,
        reverifiedTrialBalance: PeriodTrialBalance,
        outbox: OutboxMessage,
    ): ClosedPeriodRecord = Panache.withTransaction {
        find("periodType = ?1 and periodFrom = ?2", record.period.type.name, record.period.from)
            .firstResult()
            .flatMap { existing ->
                checkNotNull(existing) { "Closed period ${record.period.label} vanished during freeze" }
                existing.status = record.status.name
                existing.evidenceState = record.evidenceState.name
                existing.frozenBy = record.frozenBy
                existing.frozenAt = record.frozenAt
                existing.updatedAt = Instant.now(clock)
                val lines = reverifiedTrialBalance.lines.map { it.toEntity(record.id) }
                Panache.getSession().flatMap { session ->
                    // `voidItem()` emits null, so chaining a Kotlin non-null lambda here turns a
                    // successful first write into an NPE. Join the writes instead: all INSERTs and
                    // the outbox row remain in this one transaction; any failure rolls every one back.
                    Uni.join().all(lines.map { session.persist(it) }).andFailFast()
                        .flatMap { outboxRepository.persistInTransaction(outbox) }
                        .map { existing }
                }
            }
    }.awaitSuspending().toDomain()

    private fun ClosedPeriodRecord.toEntity() = ClosedPeriodEntity().also {
        it.id = id
        it.periodType = period.type.name
        it.periodFrom = period.from
        it.periodTo = period.to
        it.status = status.name
        it.evidenceState = evidenceState.name
        it.computedAt = computedAt
        it.totalDebits = totalDebits
        it.totalCredits = totalCredits
        it.accountCount = accountCount
        it.contentHash = contentHash
        it.draftedBy = draftedBy
        it.frozenBy = frozenBy
        it.frozenAt = frozenAt
        it.createdAt = Instant.now(clock)
        it.updatedAt = Instant.now(clock)
    }

    private fun ClosedPeriodEntity.toDomain() = ClosedPeriodRecord(
        id = id,
        period = AccountingPeriod(PeriodType.valueOf(periodType), periodFrom, periodTo),
        status = ClosedPeriodStatus.valueOf(status),
        evidenceState = ClosedPeriodEvidenceState.valueOf(evidenceState),
        computedAt = computedAt,
        totalDebits = totalDebits,
        totalCredits = totalCredits,
        accountCount = accountCount,
        contentHash = contentHash,
        draftedBy = draftedBy,
        frozenBy = frozenBy,
        frozenAt = frozenAt,
    )

    private fun TrialBalanceLine.toEntity(periodId: UUID) = ClosedPeriodTrialBalanceLineEntity().also {
        it.periodId = periodId
        it.glAccountId = glAccountId
        it.currency = currency
        it.code = code
        it.name = name
        it.accountType = type.name
        it.totalDebit = totalDebit
        it.totalCredit = totalCredit
    }

    private fun ClosedPeriodTrialBalanceLineEntity.toDomain() = TrialBalanceLine(
        glAccountId = glAccountId,
        code = code,
        name = name,
        type = com.openbank.ledger.domain.model.GlAccountType.valueOf(accountType),
        currency = currency,
        totalDebit = totalDebit,
        totalCredit = totalCredit,
    )
}
