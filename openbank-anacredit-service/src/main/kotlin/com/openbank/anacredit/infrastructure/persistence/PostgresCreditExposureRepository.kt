// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.persistence

import com.openbank.anacredit.application.port.out.CreditExposureRepository
import com.openbank.anacredit.domain.model.CounterpartyType
import com.openbank.anacredit.domain.model.CreditExposure
import com.openbank.anacredit.domain.model.InstrumentType
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.OffsetDateTime

/**
 * Postgres-backed credit-exposure repository (ADR-0037 v2). Replaces the in-memory
 * `ConcurrentHashMap` so registered exposures survive a restart — every field of
 * [CreditExposure] is a first-class report/threshold attribute, so the row is fully relational
 * (no JSONB payload, unlike openbank-product-catalog's document-shaped catalogue). Reactive
 * Panache (the fleet standard); each Mutiny [Uni] is bridged to the suspend port.
 */
@ApplicationScoped
class PostgresCreditExposureRepository(private val sf: Mutiny.SessionFactory) : CreditExposureRepository {

    override suspend fun upsert(exposure: CreditExposure): CreditExposure = sf.withTransaction { s ->
        s.find(CreditExposureEntity::class.java, exposure.instrumentId).flatMap { existing ->
            if (existing != null) {
                existing.applyFrom(exposure) // managed — flushes on commit
                Uni.createFrom().voidItem()
            } else {
                s.persist(CreditExposureEntity().apply { applyFrom(exposure) })
            }
        }
    }.replaceWith(exposure).awaitSuspending()

    override suspend fun findById(instrumentId: String): CreditExposure? =
        sf.withSession { s -> s.find(CreditExposureEntity::class.java, instrumentId) }
            .map { it?.toDomain() }
            .awaitSuspending()

    override suspend fun listAll(): List<CreditExposure> = sf.withSession { s ->
        s.createQuery(
            "FROM CreditExposureEntity ORDER BY instrumentId",
            CreditExposureEntity::class.java,
        ).resultList
    }.map { rows -> rows.map { it.toDomain() } }.awaitSuspending()

    private fun CreditExposureEntity.applyFrom(e: CreditExposure) {
        instrumentId = e.instrumentId
        debtorId = e.debtorId
        debtorType = e.debtorType.name
        instrumentType = e.instrumentType.name
        currency = e.currency
        committedAmount = e.committedAmount
        drawnAmount = e.drawnAmount
        committedAmountEur = e.committedAmountEur
        arrearsAmount = e.arrearsAmount
        defaulted = e.defaulted
        originationDate = e.originationDate
        updatedAt = OffsetDateTime.now()
    }

    private fun CreditExposureEntity.toDomain(): CreditExposure = CreditExposure(
        instrumentId = instrumentId,
        debtorId = debtorId,
        debtorType = CounterpartyType.valueOf(debtorType),
        instrumentType = InstrumentType.valueOf(instrumentType),
        currency = currency,
        committedAmount = committedAmount,
        drawnAmount = drawnAmount,
        committedAmountEur = committedAmountEur,
        arrearsAmount = arrearsAmount,
        defaulted = defaulted,
        originationDate = originationDate,
    )
}
