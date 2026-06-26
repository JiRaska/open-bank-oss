// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.interest.infrastructure.persistence.repository

import com.openbank.interest.application.port.out.*
import com.openbank.interest.domain.model.*
import com.openbank.interest.infrastructure.persistence.entity.*
import com.openbank.interest.infrastructure.persistence.mapper.InterestMapper
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class InterestRateConfigRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory, private val mapper: InterestMapper
) : InterestRateConfigRepository {
    @WithTransaction override fun save(config: InterestRateConfig): Uni<InterestRateConfig> {
        val e = mapper.toEntity(config)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }
    @WithSession override fun findById(id: UUID): Uni<InterestRateConfig?> =
        sf.withSession { s -> s.find(InterestRateConfigEntity::class.java, id) }.map { it?.let(mapper::toDomain) }
    @WithSession override fun findByProductId(productId: String): Uni<List<InterestRateConfig>> =
        sf.withSession { s -> s.createQuery("FROM InterestRateConfigEntity WHERE productId = :p ORDER BY effectiveFrom DESC", InterestRateConfigEntity::class.java).setParameter("p", productId).resultList }.map { it.map(mapper::toDomain) }
    @WithSession override fun findAll(): Uni<List<InterestRateConfig>> =
        sf.withSession { s -> s.createQuery("FROM InterestRateConfigEntity ORDER BY productId, effectiveFrom DESC", InterestRateConfigEntity::class.java).resultList }.map { it.map(mapper::toDomain) }
    @WithSession override fun findActiveForProduct(productId: String, date: LocalDate): Uni<InterestRateConfig?> =
        sf.withSession { s -> s.createQuery("FROM InterestRateConfigEntity WHERE productId = :p AND active = true AND effectiveFrom <= :d AND (effectiveTo IS NULL OR effectiveTo >= :d) ORDER BY effectiveFrom DESC", InterestRateConfigEntity::class.java).setParameter("p", productId).setParameter("d", date).setMaxResults(1).singleResultOrNull }.map { it?.let(mapper::toDomain) }
    @WithTransaction override fun update(config: InterestRateConfig): Uni<InterestRateConfig> =
        sf.withTransaction { s -> s.find(InterestRateConfigEntity::class.java, config.id).flatMap { e -> e!!.active = config.active; e.updatedAt = config.updatedAt; s.persist(e).map { mapper.toDomain(e) } } }
}

@ApplicationScoped
class InterestAccrualRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory, private val mapper: InterestMapper
) : InterestAccrualRepository {
    @WithTransaction override fun save(accrual: InterestAccrual): Uni<InterestAccrual> {
        val e = mapper.toEntity(accrual)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }
    @WithSession override fun findAll(): Uni<List<InterestAccrual>> =
        sf.withSession { s ->
            s.createQuery("FROM InterestAccrualEntity ORDER BY accrualDate DESC, createdAt DESC", InterestAccrualEntity::class.java).resultList
        }.map { it.map(mapper::toDomain) }
    @WithSession override fun findByAccountId(accountId: UUID, from: LocalDate?, to: LocalDate?): Uni<List<InterestAccrual>> {
        val q = if (from != null && to != null)
            sf.withSession { s -> s.createQuery("FROM InterestAccrualEntity WHERE accountId = :a AND accrualDate >= :f AND accrualDate <= :t ORDER BY accrualDate DESC", InterestAccrualEntity::class.java).setParameter("a", accountId).setParameter("f", from).setParameter("t", to).resultList }
        else
            sf.withSession { s -> s.createQuery("FROM InterestAccrualEntity WHERE accountId = :a ORDER BY accrualDate DESC", InterestAccrualEntity::class.java).setParameter("a", accountId).resultList }
        return q.map { it.map(mapper::toDomain) }
    }
    @WithSession override fun findPendingCapitalization(accountId: UUID, toDate: LocalDate): Uni<List<InterestAccrual>> =
        sf.withSession { s -> s.createQuery("FROM InterestAccrualEntity WHERE accountId = :a AND status = 'ACCRUING' AND accrualDate <= :d ORDER BY accrualDate ASC", InterestAccrualEntity::class.java).setParameter("a", accountId).setParameter("d", toDate).resultList }.map { it.map(mapper::toDomain) }
    @WithTransaction override fun markCapitalized(ids: List<UUID>, capitalizedAt: OffsetDateTime): Uni<Int> =
        sf.withTransaction { s -> s.createMutationQuery("UPDATE InterestAccrualEntity SET status = 'CAPITALIZED', capitalizedAt = :t WHERE id IN :ids").setParameter("t", capitalizedAt).setParameter("ids", ids).executeUpdate() }
    @WithSession override fun sumAccrued(accountId: UUID, from: LocalDate, to: LocalDate): Uni<BigDecimal> =
        sf.withSession { s -> s.createQuery("SELECT COALESCE(SUM(accruedAmount), 0) FROM InterestAccrualEntity WHERE accountId = :a AND accrualDate >= :f AND accrualDate <= :t AND status = 'ACCRUING'", BigDecimal::class.java).setParameter("a", accountId).setParameter("f", from).setParameter("t", to).singleResult }
}

@ApplicationScoped
class InterestCapitalizationRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory, private val mapper: InterestMapper
) : InterestCapitalizationRepository {
    @WithTransaction override fun save(cap: InterestCapitalization): Uni<InterestCapitalization> {
        val e = mapper.toEntity(cap)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }
    @WithSession override fun findByAccountId(accountId: UUID): Uni<List<InterestCapitalization>> =
        sf.withSession { s -> s.createQuery("FROM InterestCapitalizationEntity WHERE accountId = :a ORDER BY createdAt DESC", InterestCapitalizationEntity::class.java).setParameter("a", accountId).resultList }.map { it.map(mapper::toDomain) }
}
