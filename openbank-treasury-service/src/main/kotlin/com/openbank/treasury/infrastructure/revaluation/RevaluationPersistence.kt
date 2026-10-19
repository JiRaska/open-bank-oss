// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.revaluation

import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.RevaluationRecord
import com.openbank.treasury.application.port.out.RevaluationRepository
import com.openbank.treasury.infrastructure.persistence.repository.DealRepositoryImpl
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.pgclient.PgException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.PersistenceException
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Composite key of [DealRevaluationEntity]: one revaluation per deal and valuation date. */
data class DealRevaluationKey(var dealId: UUID? = null, var asOf: LocalDate? = null) : Serializable

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
 * ADR-0344 D7: the revaluation store. The row and its journal reference commit in ONE transaction;
 * the (deal, date) primary key is the replay guard — the loser of a race reads the winner's row.
 */
@ApplicationScoped
class RevaluationRepositoryImpl(
    private val rows: DealRevaluationPanacheRepository,
    private val deals: DealRepositoryImpl,
) : RevaluationRepository {

    override suspend fun find(dealId: UUID, asOf: LocalDate): RevaluationRecord? = Panache.withSession {
        rows.find("dealId = ?1 and asOf = ?2", dealId, asOf).firstResult()
    }.awaitSuspending()?.toRecord()

    override suspend fun cumulativeBefore(dealId: UUID, asOf: LocalDate): BigDecimal = Panache.withSession {
        rows.find("dealId = ?1 and asOf < ?2 order by asOf desc", dealId, asOf).firstResult()
    }.awaitSuspending()?.cumulative ?: BigDecimal.ZERO

    override suspend fun forDate(asOf: LocalDate, limit: Int): List<RevaluationRecord> = Panache.withSession {
        rows.find("asOf = ?1 order by dealId", asOf).page(0, limit).list()
    }.awaitSuspending().map { it.toRecord() }

    override suspend fun save(record: RevaluationRecord, journal: LedgerJournalRef?): Boolean {
        try {
            Panache.withTransaction {
                rows.persist(record.toEntity()).flatMap { _ ->
                    journal?.let { deals.persistJournal(it) } ?: io.smallrye.mutiny.Uni.createFrom().voidItem()
                }
            }.awaitSuspending()
            return true
        } catch (e: PersistenceException) {
            if (find(record.dealId, record.asOf) == null) throw e
        } catch (e: PgException) {
            if (find(record.dealId, record.asOf) == null) throw e
        }
        return false
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
