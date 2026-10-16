// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.persistence

import com.openbank.tax.application.port.out.IncomeCreditRepository
import com.openbank.tax.domain.returns.IncomeCreditTotals
import com.openbank.tax.domain.returns.ObservedIncomeCredit
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
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
@Table(name = "observed_income_credit")
class ObservedIncomeCreditEntity : PanacheEntityBase {
    @Id
    @Column(name = "withholding_id")
    var withholdingId: UUID = UUID(0L, 0L)

    @Column(name = "capitalization_id", nullable = false)
    var capitalizationId: UUID = UUID(0L, 0L)

    @Column(name = "account_id", nullable = false)
    var accountId: UUID = UUID(0L, 0L)

    @Column(name = "product_id", nullable = false)
    var productId: UUID = UUID(0L, 0L)

    @Column(name = "period_from", nullable = false)
    var periodFrom: LocalDate = LocalDate.EPOCH

    @Column(name = "period_to", nullable = false)
    var periodTo: LocalDate = LocalDate.EPOCH

    @Column(name = "currency", nullable = false)
    var currency: String = ""

    @Column(name = "gross_amount", nullable = false)
    var grossAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "net_amount", nullable = false)
    var netAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.EPOCH

    @Column(name = "observed_at", nullable = false)
    var observedAt: Instant = Instant.EPOCH
}

/**
 * See [IncomeCreditRepository]. The totals query groups by (account, currency) over credits whose
 * accrual period ENDS in the reporting window — a credit belongs to the period in which the income
 * was credited, which is what CRS reports.
 */
@ApplicationScoped
class PanacheIncomeCreditRepository :
    IncomeCreditRepository,
    PanacheRepositoryBase<ObservedIncomeCreditEntity, UUID> {

    /**
     * Idempotent on [ObservedIncomeCredit.withholdingId]: a redelivered record is a no-op
     * (`false`), the same dedup shape as the §38d observed remittance — a retry cannot double
     * an account's gross into a CRS line.
     */
    override suspend fun record(credit: ObservedIncomeCredit): Boolean = Panache.withTransaction {
        find("withholdingId", credit.withholdingId).firstResult()
            .onItem().transformToUni { existing ->
                if (existing != null) {
                    io.smallrye.mutiny.Uni.createFrom().item(false)
                } else {
                    persist(credit.toEntity()).map { true }
                }
            }
    }.awaitSuspending()

    override suspend fun totalsByAccount(from: LocalDate, to: LocalDate): List<IncomeCreditTotals> =
        Panache.withSession {
            list(
                "select accountId, currency, sum(grossAmount) from ObservedIncomeCreditEntity " +
                    "where periodTo >= ?1 and periodTo <= ?2 group by accountId, currency",
                from,
                to,
            )
        }.awaitSuspending().map { row ->
            val cells = row as Array<*>
            IncomeCreditTotals(
                accountId = cells[0] as UUID,
                currency = cells[1] as String,
                gross = cells[2] as BigDecimal,
            )
        }

    private fun ObservedIncomeCredit.toEntity() = ObservedIncomeCreditEntity().also {
        it.withholdingId = withholdingId
        it.capitalizationId = capitalizationId
        it.accountId = accountId
        it.productId = productId
        it.periodFrom = periodFrom
        it.periodTo = periodTo
        it.currency = currency
        it.grossAmount = grossAmount
        it.netAmount = netAmount
        it.occurredAt = occurredAt
        it.observedAt = observedAt
    }
}
