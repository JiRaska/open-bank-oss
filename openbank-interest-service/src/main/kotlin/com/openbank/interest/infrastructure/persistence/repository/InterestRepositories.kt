// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.persistence.repository

import com.openbank.interest.application.port.out.*
import com.openbank.interest.domain.model.*
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.interest.infrastructure.persistence.entity.InterestAccrualEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestCapitalizationEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestOutboxEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestRateConfigEntity
import com.openbank.interest.infrastructure.persistence.mapper.InterestMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class InterestRateConfigRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: InterestMapper,
) : InterestRateConfigRepository {
    @WithTransaction override fun save(config: InterestRateConfig): Uni<InterestRateConfig> {
        val e = mapper.toEntity(config)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    @WithSession override fun findById(id: UUID): Uni<InterestRateConfig?> =
        sf.withSession { s -> s.find(InterestRateConfigEntity::class.java, id) }.map { it?.let(mapper::toDomain) }

    @WithSession override fun findByProductId(productId: String): Uni<List<InterestRateConfig>> = sf.withSession { s ->
        s.createQuery(
            "FROM InterestRateConfigEntity WHERE productId = :p ORDER BY effectiveFrom DESC",
            InterestRateConfigEntity::class.java,
        ).setParameter("p", productId).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession override fun findAll(): Uni<List<InterestRateConfig>> = sf.withSession { s ->
        s.createQuery(
            "FROM InterestRateConfigEntity ORDER BY productId, effectiveFrom DESC",
            InterestRateConfigEntity::class.java,
        ).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession override fun findActiveForProduct(productId: String, date: LocalDate): Uni<InterestRateConfig?> =
        sf.withSession { s ->
            s.createQuery(
                "FROM InterestRateConfigEntity WHERE productId = :p AND active = true AND effectiveFrom <= :d AND (effectiveTo IS NULL OR effectiveTo >= :d) ORDER BY effectiveFrom DESC",
                InterestRateConfigEntity::class.java,
            ).setParameter("p", productId).setParameter("d", date).setMaxResults(1).singleResultOrNull
        }.map { it?.let(mapper::toDomain) }

    @WithSession
    override fun findEffectiveRate(
        accountId: UUID,
        productId: String,
        date: LocalDate,
        currency: String?,
    ): Uni<InterestRateConfig?> = sf.withSession { s ->
        // account-specific override (accountId set) OR the product-wide default (accountId null);
        // the CASE orders overrides (0) before defaults (1) so setMaxResults(1) picks the override.
        // currency == null keeps the currency-agnostic view (the read-only effective-rate lookup);
        // accrue passes a non-null currency so a rate resolves only in its own currency (issue #1265).
        s.createQuery(
            "FROM InterestRateConfigEntity WHERE active = true AND effectiveFrom <= :d " +
                "AND (effectiveTo IS NULL OR effectiveTo >= :d) " +
                "AND (:ccy IS NULL OR currency = :ccy) " +
                "AND (accountId = :a OR (accountId IS NULL AND productId = :p)) " +
                "ORDER BY CASE WHEN accountId IS NULL THEN 1 ELSE 0 END, effectiveFrom DESC",
            InterestRateConfigEntity::class.java,
        ).setParameter("a", accountId).setParameter("p", productId).setParameter("d", date)
            .setParameter("ccy", currency)
            .setMaxResults(1).singleResultOrNull
    }.map { it?.let(mapper::toDomain) }

    @WithTransaction
    override fun update(config: InterestRateConfig): Uni<InterestRateConfig> = sf.withTransaction { s ->
        s.find(InterestRateConfigEntity::class.java, config.id).flatMap { e ->
            e!!.active =
                config.active
            e.updatedAt = config.updatedAt
            s.persist(e).map { mapper.toDomain(e) }
        }
    }
}

@ApplicationScoped
class InterestAccrualRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: InterestMapper,
) : InterestAccrualRepository {
    @WithTransaction override fun save(accrual: InterestAccrual): Uni<InterestAccrual> {
        val e = mapper.toEntity(accrual)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    @WithSession override fun findAll(): Uni<List<InterestAccrual>> = sf.withSession { s ->
        s.createQuery(
            "FROM InterestAccrualEntity ORDER BY accrualDate DESC, createdAt DESC",
            InterestAccrualEntity::class.java,
        ).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession
    override fun findByAccountId(accountId: UUID, from: LocalDate?, to: LocalDate?): Uni<List<InterestAccrual>> {
        val q = if (from != null && to != null) {
            sf.withSession { s ->
                s.createQuery(
                    "FROM InterestAccrualEntity WHERE accountId = :a AND accrualDate >= :f AND accrualDate <= :t ORDER BY accrualDate DESC",
                    InterestAccrualEntity::class.java,
                ).setParameter("a", accountId).setParameter("f", from).setParameter("t", to).resultList
            }
        } else {
            sf.withSession { s ->
                s.createQuery(
                    "FROM InterestAccrualEntity WHERE accountId = :a ORDER BY accrualDate DESC",
                    InterestAccrualEntity::class.java,
                ).setParameter("a", accountId).resultList
            }
        }
        return q.map { it.map(mapper::toDomain) }
    }

    @WithSession override fun findPendingCapitalization(
        accountId: UUID,
        productId: String,
        toDate: LocalDate,
    ): Uni<List<InterestAccrual>> = sf.withSession { s ->
        s.createQuery(
            "FROM InterestAccrualEntity WHERE accountId = :a AND productId = :p AND status = 'ACCRUING' AND accrualDate <= :d ORDER BY accrualDate ASC",
            InterestAccrualEntity::class.java,
        ).setParameter("a", accountId).setParameter("p", productId).setParameter("d", toDate).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession override fun findAccountsWithPendingCapitalization(toDate: LocalDate): Uni<List<Pair<UUID, String>>> =
        sf.withSession { s ->
            s.createQuery(
                "SELECT DISTINCT a.accountId, a.productId FROM InterestAccrualEntity a " +
                    "WHERE a.status = 'ACCRUING' AND a.accrualDate <= :d",
                Array<Any>::class.java,
            ).setParameter("d", toDate).resultList
        }.map { rows -> rows.map { (it[0] as UUID) to (it[1] as String) } }

    @WithSession
    override fun findClaimedForCapitalization(accountId: UUID, productId: String): Uni<List<InterestAccrual>> =
        sf.withSession { s ->
            s.createQuery(
                "FROM InterestAccrualEntity WHERE accountId = :a AND productId = :p AND status = 'CAPITALIZING' ORDER BY accrualDate ASC",
                InterestAccrualEntity::class.java,
            ).setParameter("a", accountId).setParameter("p", productId).resultList
        }.map { it.map(mapper::toDomain) }

    // Own transaction, committed BEFORE the ledger post: the claim must survive a crash, or the
    // retry would re-derive a different accrual set — or a different tax profile (#1355) — and diverge
    // from the journal already booked. The resolved [profile] is frozen here alongside the period.
    @WithTransaction
    override fun claimForCapitalization(accrualIds: List<UUID>, periodTo: LocalDate, profile: TaxProfile): Uni<Unit> =
        sf.withTransaction { s ->
            s.createMutationQuery(
                "UPDATE InterestAccrualEntity SET status = 'CAPITALIZING', claimedPeriodTo = :t, " +
                    "claimedTaxpayerType = :tt, claimedResidency = :res, claimedTreatyRate = :tr, " +
                    "claimedNonCooperatingState = :ncs, claimedExemptCode = :ec " +
                    "WHERE id IN :ids AND status = 'ACCRUING'",
            ).setParameter("t", periodTo)
                .setParameter("tt", profile.taxpayerType)
                .setParameter("res", profile.residency)
                .setParameter("tr", profile.treatyRate)
                .setParameter("ncs", profile.nonCooperatingState)
                .setParameter("ec", profile.exemptCode)
                .setParameter("ids", accrualIds).executeUpdate()
                .flatMap { claimed ->
                    if (claimed != accrualIds.size) {
                        Uni.createFrom().failure(
                            IllegalStateException(
                                "Capitalization claim aborted: expected to claim ${accrualIds.size} ACCRUING " +
                                    "accruals for periodTo=$periodTo, matched $claimed — concurrent capitalization " +
                                    "or reversed accruals; rolled back, nothing was posted",
                            ),
                        )
                    } else {
                        Uni.createFrom().item(Unit)
                    }
                }
        }

    @WithSession
    override fun sumAccrued(accountId: UUID, from: LocalDate, to: LocalDate): Uni<BigDecimal> = sf.withSession { s ->
        s.createQuery(
            "SELECT COALESCE(SUM(accruedAmount), 0) FROM InterestAccrualEntity WHERE accountId = :a AND accrualDate >= :f AND accrualDate <= :t AND status = 'ACCRUING'",
            BigDecimal::class.java,
        ).setParameter("a", accountId).setParameter("f", from).setParameter("t", to).singleResult
    }
}

@ApplicationScoped
class InterestCapitalizationRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: InterestMapper,
    private val clock: Clock,
) : InterestCapitalizationRepository {
    @WithTransaction override fun save(cap: InterestCapitalization): Uni<InterestCapitalization> {
        val e = mapper.toEntity(cap)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    @WithSession
    override fun findByAccountId(accountId: UUID): Uni<List<InterestCapitalization>> = sf.withSession { s ->
        s.createQuery(
            "FROM InterestCapitalizationEntity WHERE accountId = :a ORDER BY createdAt DESC",
            InterestCapitalizationEntity::class.java,
        ).setParameter("a", accountId).resultList
    }.map { it.map(mapper::toDomain) }

    // All four writes of a capitalization share ONE transaction (same shape as statement-service's
    // saveWithOutbox): capitalization + withholding + outbox event + the guarded accrual flip. A
    // failure anywhere — including the row-count guard below — rolls the whole set back, so a retry
    // never re-credits accruals that a previous partial run already capitalized. The accruals are
    // expected to be CAPITALIZING here: InterestAccrualRepository.claimForCapitalization put them
    // there before the ledger was told anything.
    @WithTransaction
    override fun saveWithOutbox(
        cap: InterestCapitalization,
        withholding: WithholdingTax,
        event: OutboxMessage,
        accrualIds: List<UUID>,
        capitalizedAt: OffsetDateTime,
    ): Uni<InterestCapitalization> {
        val capEntity = mapper.toEntity(cap)
        val whtEntity = mapper.toEntity(withholding)
        val outboxEntity = InterestOutboxEntity().apply {
            eventId = event.eventId
            synthetic = event.synthetic
            aggregateId = event.aggregateId
            eventType = event.eventType
            payload = event.payload
            status = OutboxStatus.PENDING.name
            attemptCount = 0
            createdAt = event.createdAt
            updatedAt = clock.instant()
        }
        return sf.withTransaction { s ->
            s.persist(capEntity)
                .chain { _ -> s.persist(whtEntity) }
                .chain { _ -> s.persist(outboxEntity) }
                .chain { _ ->
                    // Status guard: only rows still CAPITALIZING — i.e. the exact set THIS attempt
                    // claimed before it posted — flip. A count mismatch means another writer
                    // capitalized (or reversed) some of them meanwhile: abort + roll back.
                    s.createMutationQuery(
                        "UPDATE InterestAccrualEntity SET status = 'CAPITALIZED', capitalizedAt = :t " +
                            "WHERE id IN :ids AND status = 'CAPITALIZING'",
                    ).setParameter("t", capitalizedAt).setParameter("ids", accrualIds).executeUpdate()
                }
                .flatMap { updated ->
                    if (updated != accrualIds.size) {
                        Uni.createFrom().failure(
                            IllegalStateException(
                                "Capitalization aborted: expected to flip ${accrualIds.size} CAPITALIZING accruals, " +
                                    "matched $updated (account=${cap.accountId}, product=${cap.productId}, " +
                                    "periodTo=${cap.periodTo}) — concurrent capitalization or reversed accruals; rolled back",
                            ),
                        )
                    } else {
                        Uni.createFrom().item(mapper.toDomain(capEntity))
                    }
                }
        }
    }
}
