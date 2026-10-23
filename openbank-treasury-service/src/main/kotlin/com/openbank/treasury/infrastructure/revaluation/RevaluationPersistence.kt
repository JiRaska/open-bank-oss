// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.revaluation

import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.PriorRevaluationUnpostedException
import com.openbank.treasury.application.port.out.RevaluationClaim
import com.openbank.treasury.application.port.out.RevaluationOutOfOrderException
import com.openbank.treasury.application.port.out.RevaluationRecord
import com.openbank.treasury.application.port.out.RevaluationRepository
import com.openbank.treasury.application.port.out.UnpostedRevaluations
import com.openbank.treasury.infrastructure.persistence.repository.DealRepositoryImpl
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Composite key of [DealRevaluationEntity]: one revaluation per deal and valuation date. */
data class DealRevaluationKey(var dealId: UUID? = null, var asOf: LocalDate? = null) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Entity
@Table(name = "deal_revaluations")
@IdClass(DealRevaluationKey::class)
class DealRevaluationEntity : PanacheEntityBase {
    @Id
    @Column(name = "deal_id", nullable = false)
    lateinit var dealId: UUID

    @Id
    @Column(name = "as_of", nullable = false)
    lateinit var asOf: LocalDate

    @Column(name = "snapshot_id", nullable = false)
    lateinit var snapshotId: UUID

    @Column(name = "fair_value", nullable = false)
    lateinit var fairValue: BigDecimal

    @Column(name = "carrying", nullable = false)
    lateinit var carrying: BigDecimal

    @Column(name = "cumulative", nullable = false)
    lateinit var cumulative: BigDecimal

    @Column(name = "change_amount", nullable = false)
    lateinit var changeAmount: BigDecimal

    @Column(name = "idempotency_key")
    var idempotencyKey: String? = null

    @Column(name = "journal_id")
    var journalId: UUID? = null

    @Column(name = "recorded_at", nullable = false)
    lateinit var recordedAt: Instant
}

@ApplicationScoped
class DealRevaluationPanacheRepository : PanacheRepositoryBase<DealRevaluationEntity, DealRevaluationKey>

/**
 * ADR-0344 D7: the revaluation store. A claim takes a per-deal transaction advisory lock, so the
 * revaluations of one deal are serialised and strictly increasing in date; the journal is attached
 * (row + deal journal reference, one transaction) once the ledger has accepted it.
 */
@ApplicationScoped
class RevaluationRepositoryImpl(
    private val rows: DealRevaluationPanacheRepository,
    private val deals: DealRepositoryImpl,
) : RevaluationRepository {

    override suspend fun find(dealId: UUID, asOf: LocalDate): RevaluationRecord? = Panache.withSession {
        rows.find("dealId = ?1 and asOf = ?2", dealId, asOf).firstResult()
    }.awaitSuspending()?.toRecord()

    override suspend fun forDate(asOf: LocalDate, limit: Int): List<RevaluationRecord> = Panache.withSession {
        rows.find("asOf = ?1 order by dealId", asOf).page(0, limit).list()
    }.awaitSuspending().map { it.toRecord() }

    override suspend fun claim(
        dealId: UUID,
        asOf: LocalDate,
        snapshotId: UUID,
        fairValue: BigDecimal,
        carrying: BigDecimal,
        target: BigDecimal,
        keyOf: (change: BigDecimal) -> String?,
        at: Instant,
    ): RevaluationClaim = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Int>("select 1 from (select pg_advisory_xact_lock(hashtext(:k))) l")
                .setParameter("k", "treasury-revaluation:$dealId").singleResult
        }.flatMap {
            rows.find("dealId = ?1 order by asOf desc", dealId).firstResult()
        }.flatMap { latest ->
            val owed = latest != null && latest.idempotencyKey != null && latest.journalId == null
            if (owed && latest!!.asOf != asOf) {
                // Never measure a new date against a change the ledger has not taken.
                throw PriorRevaluationUnpostedException(dealId, asOf, latest.asOf)
            }
            if (latest != null && !latest.asOf.isBefore(asOf)) {
                // An owed row is re-posted by its own stored change whatever the retry was valued at.
                val replay = owed ||
                    (
                        latest.asOf == asOf &&
                            latest.snapshotId == snapshotId &&
                            latest.fairValue.compareTo(fairValue) == 0
                        )
                if (!replay) throw RevaluationOutOfOrderException(dealId, asOf, latest.asOf)
                Uni.createFrom().item<RevaluationClaim>(RevaluationClaim.Existing(latest.toRecord()))
            } else {
                val change = target - (latest?.cumulative ?: BigDecimal.ZERO)
                val record = RevaluationRecord(
                    dealId,
                    asOf,
                    snapshotId,
                    fairValue,
                    carrying,
                    target,
                    change,
                    keyOf(change),
                    null,
                    at,
                )
                rows.persist(record.toEntity()).map<RevaluationClaim> { RevaluationClaim.Claimed(record) }
            }
        }
    }.awaitSuspending()

    override suspend fun owed(limit: Int): List<RevaluationRecord> = Panache.withSession {
        rows.find("idempotencyKey is not null and journalId is null order by recordedAt").page(0, limit).list()
    }.awaitSuspending().map { it.toRecord() }

    override suspend fun unposted(): UnpostedRevaluations {
        val owed = Panache.withSession {
            rows.find("idempotencyKey is not null and journalId is null order by recordedAt").list()
        }.awaitSuspending()
        return UnpostedRevaluations(owed.size, owed.firstOrNull()?.recordedAt)
    }

    override suspend fun attachJournal(dealId: UUID, asOf: LocalDate, journal: LedgerJournalRef) {
        Panache.withTransaction {
            // Row lock: a replay that re-posted the same key races the original attach; one attaches.
            rows.find("dealId = ?1 and asOf = ?2", dealId, asOf).withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult().flatMap { row ->
                    checkNotNull(row) { "revaluation of $dealId on $asOf vanished" }
                    if (row.journalId != null) {
                        Uni.createFrom().voidItem()
                    } else {
                        row.journalId = journal.journalId
                        deals.persistJournal(journal)
                    }
                }
        }.awaitSuspending()
    }

    private fun RevaluationRecord.toEntity() = DealRevaluationEntity().also {
        it.dealId = dealId
        it.asOf = asOf
        it.snapshotId = snapshotId
        it.fairValue = fairValue
        it.carrying = carrying
        it.cumulative = cumulative
        it.changeAmount = change
        it.idempotencyKey = idempotencyKey
        it.journalId = journalId
        it.recordedAt = recordedAt
    }

    private fun DealRevaluationEntity.toRecord() = RevaluationRecord(
        dealId,
        asOf,
        snapshotId,
        fairValue,
        carrying,
        cumulative,
        changeAmount,
        idempotencyKey,
        journalId,
        recordedAt,
    )
}
