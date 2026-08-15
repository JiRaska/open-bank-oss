// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.persistence.repository

import com.openbank.fx.application.port.out.FxConversionRepository
import com.openbank.fx.application.port.out.FxOutboxRepository
import com.openbank.fx.application.port.out.FxRateRepository
import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import com.openbank.fx.infrastructure.persistence.entity.FxConversionEntity
import com.openbank.fx.infrastructure.persistence.entity.FxRateEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class FxRateRepositoryImpl(private val clock: Clock) : FxRateRepository {
    @Inject
    lateinit var sf: Mutiny.SessionFactory

    override suspend fun save(rate: FxRate): FxRate {
        val e = FxRateEntity().also {
            it.id = rate.id
            it.baseCurrency = rate.baseCurrency
            it.quoteCurrency = rate.quoteCurrency
            it.bidRate = rate.bidRate
            it.askRate = rate.askRate
            it.rateType = rate.rateType.name
            it.source = rate.source.name
            it.validFrom = rate.validFrom
            it.validTo = rate.validTo
            it.createdAt = rate.createdAt
        }
        sf.withTransaction { s, _ -> s.persist(e) }.awaitSuspending()
        return rate
    }

    override suspend fun findLatest(base: String, quote: String, type: RateType): FxRate? = sf.withSession { s ->
        s.createQuery(
            "from FxRateEntity where baseCurrency=:b and quoteCurrency=:q" +
                " and rateType=:t and validTo > :now order by createdAt desc",
            FxRateEntity::class.java,
        ).setParameter("b", base).setParameter("q", quote)
            .setParameter("t", type.name).setParameter("now", Instant.now(clock))
            .setMaxResults(1).singleResultOrNull
    }.awaitSuspending()?.let {
        FxRate(
            it.id, it.baseCurrency, it.quoteCurrency, it.bidRate, it.askRate,
            type, RateSource.valueOf(it.source), it.validFrom, it.validTo, it.createdAt,
        )
    }

    override suspend fun findAll(): List<FxRate> = sf.withSession { s ->
        s.createQuery(
            "from FxRateEntity where (source=:s1 or source=:s2) and validTo > :now" +
                " order by baseCurrency, quoteCurrency",
            FxRateEntity::class.java,
        ).setParameter("s1", RateSource.INTERNAL.name).setParameter("s2", RateSource.CNB.name)
            .setParameter("now", Instant.now(clock)).resultList
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findLatestBySource(base: String, quote: String, source: RateSource): FxRate? =
        sf.withSession { s ->
            s.createQuery(
                "from FxRateEntity where baseCurrency=:b and quoteCurrency=:q and source=:src" +
                    " and validTo > :now order by validFrom desc, createdAt desc",
                FxRateEntity::class.java,
            ).setParameter("b", base).setParameter("q", quote)
                .setParameter("src", source.name).setParameter("now", Instant.now(clock))
                .setMaxResults(1).singleResultOrNull
        }.awaitSuspending()?.toDomain()

    override suspend fun findBySourceAsOf(base: String, quote: String, source: RateSource, at: Instant): FxRate? =
        sf.withSession { s ->
            s.createQuery(
                "from FxRateEntity where baseCurrency=:b and quoteCurrency=:q and source=:src" +
                    " and validFrom <= :at and validTo > :at order by validFrom desc, createdAt desc",
                FxRateEntity::class.java,
            ).setParameter("b", base).setParameter("q", quote)
                .setParameter("src", source.name).setParameter("at", at)
                .setMaxResults(1).singleResultOrNull
        }.awaitSuspending()?.toDomain()

    override suspend fun findBySourceAndValidFrom(
        base: String,
        quote: String,
        source: RateSource,
        validFrom: Instant,
    ): FxRate? = sf.withSession { s ->
        s.createQuery(
            "from FxRateEntity where baseCurrency=:b and quoteCurrency=:q" +
                " and source=:src and validFrom=:vf order by createdAt desc",
            FxRateEntity::class.java,
        ).setParameter("b", base).setParameter("q", quote)
            .setParameter("src", source.name).setParameter("vf", validFrom)
            .setMaxResults(1).singleResultOrNull
    }.awaitSuspending()?.toDomain()

    @Suppress("MagicNumber")
    override suspend fun findHistory(
        base: String,
        quote: String,
        source: RateSource?,
        from: Instant?,
        to: Instant?,
        limit: Int,
        offset: Int,
    ): List<FxRate> {
        val conditions = buildList {
            add("baseCurrency=:b and quoteCurrency=:q")
            if (source != null) add("source=:src")
            if (from != null) add("validFrom >= :from")
            if (to != null) add("validFrom <= :to")
        }
        val hql = "from FxRateEntity where ${conditions.joinToString(" and ")} order by validFrom desc"
        return sf.withSession { s ->
            s.createQuery(hql, FxRateEntity::class.java)
                .setParameter("b", base).setParameter("q", quote)
                .also { q -> if (source != null) q.setParameter("src", source.name) }
                .also { q -> if (from != null) q.setParameter("from", from) }
                .also { q -> if (to != null) q.setParameter("to", to) }
                .setFirstResult(offset).setMaxResults(limit.coerceAtMost(365))
                .resultList
        }.awaitSuspending().map { it.toDomain() }
    }

    private fun FxRateEntity.toDomain() = FxRate(
        id, baseCurrency, quoteCurrency, bidRate, askRate,
        RateType.valueOf(rateType), RateSource.valueOf(source), validFrom, validTo, createdAt,
    )
}

@ApplicationScoped
class FxConversionRepositoryImpl : FxConversionRepository {
    @Inject
    lateinit var sf: Mutiny.SessionFactory

    @Inject
    lateinit var outboxRepo: FxOutboxRepository

    override suspend fun save(conv: FxConversion): FxConversion {
        val e = conv.toEntity()
        sf.withTransaction { s, _ -> s.persist(e) }.awaitSuspending()
        return conv
    }

    override suspend fun saveWithOutbox(conv: FxConversion, outboxMessage: OutboxMessage): FxConversion {
        val e = conv.toEntity()
        sf.withTransaction { s, _ ->
            s.persist(e).chain { _ -> outboxRepo.persistInTransaction(outboxMessage) }
        }.awaitSuspending()
        return conv
    }

    private fun FxConversion.toEntity() = FxConversionEntity().also {
        it.id = id
        it.idempotencyKey = idempotencyKey
        it.partyId = partyId
        it.accountId = accountId
        it.fromCurrency = fromCurrency
        it.toCurrency = toCurrency
        it.fromAmountMinorUnits = fromAmountMinorUnits
        it.toAmountMinorUnits = toAmountMinorUnits
        it.appliedRate = appliedRate
        it.feeMinorUnits = feeMinorUnits
        it.rateId = rateId
        it.status = status.name
        it.createdAt = createdAt
        it.settledAt = settledAt
    }

    override suspend fun findById(id: UUID): FxConversion? =
        sf.withSession { s -> s.find(FxConversionEntity::class.java, id) }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(key: String): FxConversion? = sf.withSession { s ->
        s.createQuery(
            "from FxConversionEntity where idempotencyKey=:k",
            FxConversionEntity::class.java,
        ).setParameter("k", key).singleResultOrNull
    }.awaitSuspending()?.toDomain()

    override suspend fun findByPartyId(partyId: UUID): List<FxConversion> = sf.withSession { s ->
        s.createQuery(
            "from FxConversionEntity where partyId=:p order by createdAt desc",
            FxConversionEntity::class.java,
        ).setParameter("p", partyId).resultList
    }.awaitSuspending().map { it.toDomain() }

    private fun FxConversionEntity.toDomain() = FxConversion(
        id, idempotencyKey, partyId, accountId, fromCurrency, toCurrency,
        fromAmountMinorUnits, toAmountMinorUnits, appliedRate, feeMinorUnits,
        rateId, FxConversionStatus.valueOf(status), createdAt, settledAt,
    )
}
