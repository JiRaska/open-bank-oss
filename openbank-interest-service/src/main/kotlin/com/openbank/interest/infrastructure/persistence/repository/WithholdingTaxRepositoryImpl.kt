// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.persistence.repository

import com.openbank.interest.application.port.out.InterestEventOutbox
import com.openbank.interest.application.port.out.WithholdingRemittanceRepository
import com.openbank.interest.application.port.out.WithholdingTaxRepository
import com.openbank.interest.domain.tax.WithholdingRemittance
import com.openbank.interest.domain.tax.WithholdingRemittanceStatus
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.interest.domain.tax.WithholdingTaxStatus
import com.openbank.interest.infrastructure.persistence.entity.InterestOutboxEntity
import com.openbank.interest.infrastructure.persistence.entity.WithholdingRemittanceEntity
import com.openbank.interest.infrastructure.persistence.entity.WithholdingTaxEntity
import com.openbank.interest.infrastructure.persistence.mapper.InterestMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class WithholdingTaxRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: InterestMapper,
) : WithholdingTaxRepository {

    @WithTransaction override fun save(withholding: WithholdingTax): Uni<WithholdingTax> {
        val e = mapper.toEntity(withholding)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    @WithSession override fun findByAccountId(accountId: UUID): Uni<List<WithholdingTax>> = sf.withSession { s ->
        s.createQuery(
            "FROM WithholdingTaxEntity WHERE accountId = :a ORDER BY createdAt DESC",
            WithholdingTaxEntity::class.java,
        ).setParameter("a", accountId).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession
    override fun findByCapitalizationId(capitalizationId: UUID): Uni<WithholdingTax?> = sf.withSession { s ->
        s.createQuery(
            "FROM WithholdingTaxEntity WHERE capitalizationId = :c",
            WithholdingTaxEntity::class.java,
        ).setParameter("c", capitalizationId).setMaxResults(1).singleResultOrNull
    }.map { it?.let(mapper::toDomain) }

    @WithSession override fun findRecordedForPeriod(from: LocalDate, to: LocalDate): Uni<List<WithholdingTax>> =
        sf.withSession { s ->
            s.createQuery(
                "FROM WithholdingTaxEntity WHERE status = :st AND periodTo >= :from AND periodTo <= :to",
                WithholdingTaxEntity::class.java,
            ).setParameter("st", WithholdingTaxStatus.RECORDED)
                .setParameter("from", from).setParameter("to", to).resultList
        }.map { it.map(mapper::toDomain) }

    @WithTransaction override fun markRemitted(ids: List<UUID>, remittanceId: UUID): Uni<Int> {
        if (ids.isEmpty()) return Uni.createFrom().item(0)
        return sf.withTransaction { s ->
            // Status guard: only RECORDED rows may advance to REMITTED — an unguarded bulk update
            // would silently re-stamp rows already folded into another batch (or RECONCILED /
            // REVERSED ones). The caller compares the returned count against ids.size and fails
            // the assembly on a mismatch.
            s.createMutationQuery(
                "UPDATE WithholdingTaxEntity SET status = :st, remittanceId = :r " +
                    "WHERE id IN :ids AND status = :recorded",
            ).setParameter("st", WithholdingTaxStatus.REMITTED)
                .setParameter("recorded", WithholdingTaxStatus.RECORDED)
                .setParameter("r", remittanceId).setParameter("ids", ids).executeUpdate()
        }
    }
}

@ApplicationScoped
class WithholdingRemittanceRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: InterestMapper,
) : WithholdingRemittanceRepository {

    @WithTransaction override fun save(remittance: WithholdingRemittance): Uni<WithholdingRemittance> {
        val e = mapper.toEntity(remittance)
        return sf.withTransaction { s -> s.persist(e).map { remittance } }
    }

    @WithSession override fun findByPeriod(year: Int, month: Int): Uni<WithholdingRemittance?> = sf.withSession { s ->
        s.createQuery(
            "FROM WithholdingRemittanceEntity WHERE periodYear = :y AND periodMonth = :m",
            WithholdingRemittanceEntity::class.java,
        ).setParameter("y", year).setParameter("m", month).setMaxResults(1).singleResultOrNull
    }.map { it?.let(mapper::toDomain) }

    @WithSession override fun findAll(): Uni<List<WithholdingRemittance>> = sf.withSession { s ->
        s.createQuery(
            "FROM WithholdingRemittanceEntity ORDER BY periodYear DESC, periodMonth DESC, createdAt DESC",
            WithholdingRemittanceEntity::class.java,
        ).resultList
    }.map { it.map(mapper::toDomain) }

    @WithTransaction override fun markSettled(remittanceId: UUID): Uni<Int> = sf.withTransaction { s ->
        s.createMutationQuery(
            "UPDATE WithholdingRemittanceEntity SET status = :st WHERE id = :id AND status = :pending",
        ).setParameter("st", WithholdingRemittanceStatus.SETTLED)
            .setParameter("id", remittanceId)
            .setParameter("pending", WithholdingRemittanceStatus.PENDING)
            .executeUpdate()
    }
}

@ApplicationScoped
class InterestEventOutboxImpl @Inject constructor(private val sf: Mutiny.SessionFactory, private val clock: Clock) :
    InterestEventOutbox {

    @WithTransaction override fun append(message: OutboxMessage): Uni<Void> {
        val e = InterestOutboxEntity().apply {
            eventId = message.eventId
            synthetic = message.synthetic
            aggregateId = message.aggregateId
            eventType = message.eventType
            payload = message.payload
            status = OutboxStatus.PENDING.name
            attemptCount = 0
            createdAt = message.createdAt
            updatedAt = clock.instant()
        }
        return sf.withTransaction { s -> s.persist(e) }.replaceWithVoid()
    }
}
