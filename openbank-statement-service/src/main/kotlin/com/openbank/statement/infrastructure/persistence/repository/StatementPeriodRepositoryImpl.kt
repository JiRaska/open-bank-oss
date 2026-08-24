// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.statement.application.port.out.StatementOutbox
import com.openbank.statement.application.port.out.StatementPeriodRepository
import com.openbank.statement.domain.model.PeriodCloseStatus
import com.openbank.statement.domain.model.StatementPeriod
import com.openbank.statement.infrastructure.persistence.entity.StatementOutboxEntity
import com.openbank.statement.infrastructure.persistence.entity.StatementPeriodEntity
import com.openbank.statement.infrastructure.persistence.mapper.StatementMapper
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class StatementPeriodRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: StatementMapper,
    private val clock: Clock,
) : StatementPeriodRepository {

    @WithSession
    override fun nextLegalSequence(accountId: UUID, currency: String): Uni<Long> = sf.withSession { s ->
        s.createQuery(
            "SELECT COALESCE(MAX(p.legalSequenceNumber), 0) FROM StatementPeriodEntity p " +
                "WHERE p.accountId = :a AND p.pocketCurrency = :c",
            java.lang.Long::class.java,
        ).setParameter("a", accountId).setParameter("c", currency).singleResult
    }.map { (it?.toLong() ?: 0L) + 1L }

    @WithTransaction
    override fun save(period: StatementPeriod): Uni<StatementPeriod> {
        val e = mapper.toEntity(period)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    @WithTransaction
    override fun saveWithOutbox(period: StatementPeriod, event: OutboxMessage): Uni<StatementPeriod> {
        val pe = mapper.toEntity(period)
        val now = clock.instant()
        val oe = StatementOutboxEntity().apply {
            eventId = event.eventId
            synthetic = event.synthetic
            aggregateId = event.aggregateId
            eventType = event.eventType
            payload = event.payload
            status = OutboxStatus.PENDING.name
            attemptCount = 0
            createdAt = now
            updatedAt = now
        }
        // Both persists run in ONE transaction → atomic. If the outbox insert fails, the period
        // insert rolls back with it, so a CLOSED period can never exist without its emitted event.
        return sf.withTransaction { s ->
            s.persist(pe).chain { _ -> s.persist(oe) }.replaceWith(mapper.toDomain(pe))
        }
    }

    @WithTransaction
    override fun supersedeAndReplace(
        supersededId: UUID,
        replacement: StatementPeriod,
        event: OutboxMessage,
    ): Uni<StatementPeriod> {
        val pe = mapper.toEntity(replacement)
        val now = clock.instant()
        val oe = StatementOutboxEntity().apply {
            eventId = event.eventId
            synthetic = event.synthetic
            aggregateId = event.aggregateId
            eventType = event.eventType
            payload = event.payload
            status = OutboxStatus.PENDING.name
            attemptCount = 0
            createdAt = now
            updatedAt = now
        }
        return sf.withTransaction { s ->
            // The superseded row is loaded MANAGED and mutated, so Hibernate issues an UPDATE on
            // flush. Do NOT round-trip it through persist(): `id` is application-assigned, so a
            // non-null id cannot distinguish transient from detached and Panache would schedule an
            // INSERT — the duplicate-key defect that 500'd every consent-service transition.
            s.find(StatementPeriodEntity::class.java, supersededId)
                .chain { prior ->
                    requireNotNull(prior) { "No statement period $supersededId to supersede" }
                    prior.status = PeriodCloseStatus.SUPERSEDED
                    // Flip BEFORE inserting the replacement: both rows share the window, and
                    // ux_statement_period_window_active admits only one non-SUPERSEDED row.
                    s.flush()
                }
                .chain { _ -> s.persist(pe) }
                .chain { _ -> s.persist(oe) }
                .replaceWith(mapper.toDomain(pe))
        }
    }

    @WithSession
    override fun findByPeriod(
        accountId: UUID,
        currency: String,
        from: LocalDate,
        to: LocalDate,
    ): Uni<StatementPeriod?> = sf.withSession { s ->
        s.createQuery(
            "FROM StatementPeriodEntity WHERE accountId = :a AND pocketCurrency = :c " +
                "AND periodFrom = :f AND periodTo = :t AND status <> :sup",
            StatementPeriodEntity::class.java,
        ).setParameter("a", accountId).setParameter("c", currency)
            .setParameter("f", from).setParameter("t", to)
            .setParameter("sup", PeriodCloseStatus.SUPERSEDED)
            .setMaxResults(1).singleResultOrNull
    }.map { it?.let(mapper::toDomain) }

    @WithSession
    override fun findBySequence(accountId: UUID, currency: String, legalSequence: Long): Uni<StatementPeriod?> =
        sf.withSession { s ->
            s.createQuery(
                "FROM StatementPeriodEntity WHERE accountId = :a AND pocketCurrency = :c " +
                    "AND legalSequenceNumber = :n",
                StatementPeriodEntity::class.java,
            ).setParameter("a", accountId).setParameter("c", currency).setParameter("n", legalSequence)
                .setMaxResults(1).singleResultOrNull
        }.map { it?.let(mapper::toDomain) }

    @WithSession
    override fun priorClosing(accountId: UUID, currency: String, before: LocalDate): Uni<BigDecimal?> =
        sf.withSession { s ->
            s.createQuery(
                "FROM StatementPeriodEntity WHERE accountId = :a AND pocketCurrency = :c " +
                    "AND periodTo < :b AND status <> :sup ORDER BY periodTo DESC",
                StatementPeriodEntity::class.java,
            ).setParameter("a", accountId).setParameter("c", currency).setParameter("b", before)
                .setParameter("sup", PeriodCloseStatus.SUPERSEDED)
                .setMaxResults(1).singleResultOrNull
        }.map { it?.closingBalance }

    @WithSession
    override fun listForAccount(accountId: UUID): Uni<List<StatementPeriod>> = sf.withSession { s ->
        s.createQuery(
            "FROM StatementPeriodEntity WHERE accountId = :a ORDER BY periodTo DESC, pocketCurrency ASC",
            StatementPeriodEntity::class.java,
        ).setParameter("a", accountId).resultList
    }.map { list -> list.map(mapper::toDomain) }

    @WithSession
    override fun latestClosedPeriodTo(accountId: UUID, currency: String): Uni<LocalDate?> = sf.withSession { s ->
        s.createQuery(
            "SELECT MAX(p.periodTo) FROM StatementPeriodEntity p " +
                "WHERE p.accountId = :a AND p.pocketCurrency = :c AND p.status = :st",
            LocalDate::class.java,
        ).setParameter("a", accountId).setParameter("c", currency)
            .setParameter("st", PeriodCloseStatus.CLOSED)
            .singleResultOrNull
    }
}

@ApplicationScoped
class StatementOutboxImpl @Inject constructor(private val sf: Mutiny.SessionFactory, private val clock: Clock) :
    StatementOutbox {

    @WithTransaction
    override fun append(message: OutboxMessage): Uni<Void> {
        val now = clock.instant()
        val e = StatementOutboxEntity().apply {
            eventId = message.eventId
            synthetic = message.synthetic
            aggregateId = message.aggregateId
            eventType = message.eventType
            payload = message.payload
            status = OutboxStatus.PENDING.name
            attemptCount = 0
            createdAt = now
            updatedAt = now
        }
        return sf.withTransaction { s -> s.persist(e) }.replaceWithVoid()
    }
}
