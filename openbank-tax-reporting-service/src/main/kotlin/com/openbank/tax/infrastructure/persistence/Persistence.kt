// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.persistence

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.tax.application.port.out.ObservedRemittanceRepository
import com.openbank.tax.application.port.out.RemittanceTotals
import com.openbank.tax.application.port.out.TaxFilingRepository
import com.openbank.tax.domain.model.FilingPeriod
import com.openbank.tax.domain.model.FilingStatus
import com.openbank.tax.domain.model.ObservedRemittance
import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.TaxFilingRecord
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "tax_observed_remittance")
class ObservedRemittanceEntity : PanacheEntityBase {
    @Id
    @Column(name = "remittance_id")
    var remittanceId: UUID = Ids.newId()

    @Column(name = "period_year", nullable = false)
    var periodYear: Int = 0

    @Column(name = "period_month", nullable = false)
    var periodMonth: Int = 0

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = "CZK"

    @Column(name = "total_tax_amount", nullable = false)
    var totalTaxAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "item_count", nullable = false)
    var itemCount: Int = 0

    @Column(name = "due_date", nullable = false)
    var dueDate: LocalDate = LocalDate.EPOCH

    @Column(name = "observed_at", nullable = false)
    var observedAt: Instant = Instant.now()
}

@Entity
@Table(name = "tax_filing")
class TaxFilingEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = Ids.newId()

    @Column(name = "period_year", nullable = false)
    var periodYear: Int = 0

    @Column(name = "period_month", nullable = false)
    var periodMonth: Int = 0

    @Column(name = "status", nullable = false)
    var status: String = "OPEN"

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = "CZK"

    @Column(name = "total_tax_amount", nullable = false)
    var totalTaxAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "remittance_count", nullable = false)
    var remittanceCount: Int = 0

    @Column(name = "item_count", nullable = false)
    var itemCount: Int = 0

    @Column(name = "assembled_at")
    var assembledAt: Instant? = null

    @Column(name = "assembled_by")
    var assembledBy: String? = null

    @Column(name = "filed_at")
    var filedAt: Instant? = null

    @Column(name = "filed_by")
    var filedBy: String? = null

    @Column(name = "filing_reference")
    var filingReference: String? = null

    @Column(name = "version", nullable = false)
    var version: Long = 0L
}

@ApplicationScoped
class PanacheObservedRemittanceRepository :
    ObservedRemittanceRepository,
    PanacheRepositoryBase<ObservedRemittanceEntity, UUID> {

    /**
     * Insert-if-absent keyed on the remittance id. The pre-check plus the primary key together are
     * what make re-delivery a no-op: Kafka is at-least-once and this is a second consumer group, so
     * a redelivery after a rebalance is routine. Counting a batch twice would overstate the tax on
     * a statutory return.
     */
    override suspend fun record(remittance: ObservedRemittance): Boolean = Panache.withTransaction {
        findById(remittance.remittanceId).flatMap { existing ->
            if (existing != null) {
                Uni.createFrom().item(false)
            } else {
                persist(remittance.toEntity()).map { true }
            }
        }
    }.awaitSuspending()

    override suspend fun findByPeriod(period: FilingPeriod): List<ObservedRemittance> = Panache.withSession {
        find(
            "periodYear = ?1 and periodMonth = ?2 order by observedAt asc",
            period.year,
            period.month,
        ).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun totalsFor(period: FilingPeriod): RemittanceTotals {
        val rows = findByPeriod(period)
        return RemittanceTotals(
            remittanceCount = rows.size,
            itemCount = rows.sumOf { it.itemCount },
            totalTaxAmount = rows.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.totalTaxAmount) },
            currencies = rows.map { it.currency }.toSet(),
        )
    }

    private fun ObservedRemittance.toEntity() = ObservedRemittanceEntity().also {
        it.remittanceId = remittanceId
        it.periodYear = period.year
        it.periodMonth = period.month
        it.currency = currency
        it.totalTaxAmount = totalTaxAmount
        it.itemCount = itemCount
        it.dueDate = dueDate
        it.observedAt = observedAt
    }

    private fun ObservedRemittanceEntity.toDomain() = ObservedRemittance(
        remittanceId = remittanceId,
        period = FilingPeriod(periodYear, periodMonth),
        currency = currency,
        totalTaxAmount = totalTaxAmount,
        itemCount = itemCount,
        dueDate = dueDate,
        observedAt = observedAt,
    )
}

@ApplicationScoped
class PanacheTaxFilingRepository :
    TaxFilingRepository,
    PanacheRepositoryBase<TaxFilingEntity, UUID> {

    override suspend fun findByPeriod(period: FilingPeriod): TaxFilingRecord? = Panache.withSession {
        find("periodYear = ?1 and periodMonth = ?2", period.year, period.month).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun listFilings(): List<TaxFilingRecord> = Panache.withSession {
        find("order by periodYear desc, periodMonth desc").list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun openIfAbsent(record: TaxFilingRecord): TaxFilingRecord = Panache.withTransaction {
        find("periodYear = ?1 and periodMonth = ?2", record.period.year, record.period.month)
            .firstResult()
            .flatMap { existing -> existing?.let { Uni.createFrom().item(it) } ?: persist(record.toEntity()) }
    }.awaitSuspending().toDomain()

    /**
     * Conditional update guarded on the expected version, so two operators racing the same
     * transition cannot both win — the loser sees 0 rows affected and gets a conflict rather than
     * a lost update on a statutory return.
     */
    override suspend fun save(record: TaxFilingRecord, expectedVersion: Long): TaxFilingRecord =
        Panache.withTransaction {
            update(
                "status = ?1, totalTaxAmount = ?2, remittanceCount = ?3, itemCount = ?4, " +
                    "assembledAt = ?5, assembledBy = ?6, filedAt = ?7, filedBy = ?8, " +
                    "filingReference = ?9, version = ?10 where id = ?11 and version = ?12",
                record.status.name,
                record.totalTaxAmount,
                record.remittanceCount,
                record.itemCount,
                record.assembledAt,
                record.assembledBy,
                record.filedAt,
                record.filedBy,
                record.filingReference,
                record.version,
                record.id,
                expectedVersion,
            ).map { updated ->
                if (updated != 1) {
                    throw TaxConflictException(
                        "Filing ${record.period.label} changed concurrently (expected version " +
                            "$expectedVersion) — reload and retry",
                    )
                }
                record
            }
        }.awaitSuspending()

    private fun TaxFilingRecord.toEntity() = TaxFilingEntity().also {
        it.id = id
        it.periodYear = period.year
        it.periodMonth = period.month
        it.status = status.name
        it.currency = currency
        it.totalTaxAmount = totalTaxAmount
        it.remittanceCount = remittanceCount
        it.itemCount = itemCount
        it.assembledAt = assembledAt
        it.assembledBy = assembledBy
        it.filedAt = filedAt
        it.filedBy = filedBy
        it.filingReference = filingReference
        it.version = version
    }

    private fun TaxFilingEntity.toDomain() = TaxFilingRecord(
        id = id,
        period = FilingPeriod(periodYear, periodMonth),
        status = FilingStatus.valueOf(status),
        currency = currency,
        totalTaxAmount = totalTaxAmount,
        remittanceCount = remittanceCount,
        itemCount = itemCount,
        assembledAt = assembledAt,
        assembledBy = assembledBy,
        filedAt = filedAt,
        filedBy = filedBy,
        filingReference = filingReference,
        version = version,
    )
}
