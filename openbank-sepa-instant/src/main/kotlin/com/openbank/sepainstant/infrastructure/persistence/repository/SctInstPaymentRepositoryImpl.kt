// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepainstant.infrastructure.persistence.repository

import com.openbank.sepainstant.application.port.out.SctInstPaymentRepository
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.sepainstant.infrastructure.persistence.entity.SctInstPaymentEntity
import com.openbank.sepainstant.infrastructure.persistence.mapper.SctInstMapper
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class SctInstPaymentRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: SctInstMapper,
    private val clock: Clock,
) : SctInstPaymentRepository {

    @WithTransaction
    override fun save(payment: SctInstPayment): Uni<SctInstPayment> {
        val entity = mapper.toEntity(payment)
        return sf.withTransaction { s -> s.persist(entity).map { mapper.toDomain(entity) } }
    }

    @WithSession
    override fun findByPaymentId(paymentId: UUID): Uni<SctInstPayment?> = sf.withSession { s ->
        s.createQuery("FROM SctInstPaymentEntity WHERE paymentId = :id", SctInstPaymentEntity::class.java)
            .setParameter("id", paymentId).singleResultOrNull
    }.map { it?.let(mapper::toDomain) }

    @WithSession
    override fun findByIdempotencyKey(key: String): Uni<SctInstPayment?> = sf.withSession { s ->
        s.createQuery("FROM SctInstPaymentEntity WHERE idempotencyKey = :k", SctInstPaymentEntity::class.java)
            .setParameter("k", key).singleResultOrNull
    }.map { it?.let(mapper::toDomain) }

    @WithSession
    override fun findAll(): Uni<List<SctInstPayment>> = sf.withSession { s ->
        s.createQuery("FROM SctInstPaymentEntity ORDER BY createdAt DESC", SctInstPaymentEntity::class.java)
            .resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession
    override fun findByDebtorAccountId(debtorAccountId: UUID, page: Int, size: Int): Uni<List<SctInstPayment>> =
        sf.withSession { s ->
            s.createQuery(
                "FROM SctInstPaymentEntity WHERE debtorAccountId = :d ORDER BY createdAt DESC",
                SctInstPaymentEntity::class.java,
            )
                .setParameter("d", debtorAccountId).setFirstResult(page * size).setMaxResults(size).resultList
        }.map { it.map(mapper::toDomain) }

    @WithTransaction
    override fun updateStatus(paymentId: UUID, status: SctInstStatus): Uni<Int> = sf.withTransaction { s ->
        s.createMutationQuery("UPDATE SctInstPaymentEntity SET status = :s, updatedAt = :now WHERE paymentId = :id")
            .setParameter("s", status.name)
            .setParameter("now", OffsetDateTime.now(clock))
            .setParameter("id", paymentId)
            .executeUpdate()
    }

    @WithSession
    override fun findTimedOut(): Uni<List<SctInstPayment>> = sf.withSession { s ->
        s.createQuery(
            "FROM SctInstPaymentEntity WHERE status = 'PROCESSING' AND executionTimeoutAt < :now ORDER BY executionTimeoutAt ASC",
            SctInstPaymentEntity::class.java,
        )
            .setParameter("now", OffsetDateTime.now(clock)).resultList
    }.map { it.map(mapper::toDomain) }
}
