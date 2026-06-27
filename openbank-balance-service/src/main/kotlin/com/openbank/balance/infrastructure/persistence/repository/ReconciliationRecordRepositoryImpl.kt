// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.out.ReconciliationRecordRepository
import com.openbank.balance.domain.reconciliation.CurrencyReconciliation
import com.openbank.balance.domain.reconciliation.ReconciliationReport
import com.openbank.balance.infrastructure.persistence.entity.BalanceReconciliationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ReconciliationPanacheRepo : PanacheRepository<BalanceReconciliationEntity>

@ApplicationScoped
class ReconciliationRecordRepositoryImpl(
    private val repo: ReconciliationPanacheRepo,
    private val mapper: ObjectMapper,
) : ReconciliationRecordRepository {

    override suspend fun save(report: ReconciliationReport): ReconciliationReport {
        Panache.withTransaction {
            val entity = BalanceReconciliationEntity().apply {
                asOf = report.asOf
                generatedAt = report.generatedAt
                tolerance = report.tolerance
                hasDrift = report.hasDrift
                driftedCurrencies = report.driftedCurrencies.joinToString(",")
                currencies = mapper.writeValueAsString(report.currencies)
            }
            repo.persist(entity)
        }.awaitSuspending()
        return report
    }

    override suspend fun findLatest(): ReconciliationReport? = Panache.withSession {
        repo.find("order by generatedAt desc").firstResult()
    }.awaitSuspending()?.toReport()

    private fun BalanceReconciliationEntity.toReport(): ReconciliationReport {
        val lines: List<CurrencyReconciliation> = mapper.readValue(
            currencies,
            mapper.typeFactory.constructCollectionType(List::class.java, CurrencyReconciliation::class.java),
        )
        return ReconciliationReport(
            asOf = asOf,
            generatedAt = generatedAt,
            tolerance = tolerance,
            currencies = lines,
        )
    }
}
