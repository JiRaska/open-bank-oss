// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.portfolio

import com.openbank.treasury.application.port.out.DuplicatePortfolioStatementException
import com.openbank.treasury.application.port.out.PortfolioStatementRepository
import com.openbank.treasury.application.port.out.StoredPortfolioStatement
import com.openbank.treasury.domain.model.PortfolioPosition
import com.openbank.treasury.domain.model.PortfolioSnapshot
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.exception.ConstraintViolationException
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class PortfolioHoldingPanacheRepository : PanacheRepository<PortfolioHoldingEntity>

@ApplicationScoped
open class PortfolioStatementRepositoryImpl(private val holdingRepo: PortfolioHoldingPanacheRepository) :
    PortfolioStatementRepository,
    PanacheRepository<PortfolioStatementEntity> {

    override suspend fun findByIdempotencyKey(key: String): StoredPortfolioStatement? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery(
                "SELECT s.* FROM portfolio_statements s JOIN portfolio_statement_keys k " +
                    "ON k.statement_uuid = s.statement_uuid WHERE k.idempotency_key = :key",
                PortfolioStatementEntity::class.java,
            ).setParameter("key", key).resultList
        }
    }.awaitSuspending().firstOrNull()?.let { withHoldings(it) }

    override suspend fun bindIdempotencyKey(key: String, statementId: UUID): StoredPortfolioStatement {
        Panache.withTransaction { insertKey(key, statementId, ignoreConflict = true) }.awaitSuspending()
        return checkNotNull(findByIdempotencyKey(key)) { "accepted portfolio key has no stored statement" }
    }

    private fun insertKey(key: String, statementId: UUID, ignoreConflict: Boolean = false): Uni<Int> =
        Panache.getSession().flatMap { session ->
            val conflict = if (ignoreConflict) " ON CONFLICT (idempotency_key) DO NOTHING" else ""
            session.createNativeQuery<Int>(
                "INSERT INTO portfolio_statement_keys (idempotency_key, statement_uuid) " +
                    "VALUES (:key, :statement)$conflict",
            ).setParameter("key", key).setParameter("statement", statementId).executeUpdate()
        }

    override suspend fun current(entity: String, date: LocalDate): StoredPortfolioStatement? = Panache.withSession {
        find("entity = ?1 and statementDate = ?2 and supersededBy is null", entity, date).firstResult()
    }.awaitSuspending()?.let { withHoldings(it) }

    override suspend fun versions(entity: String, date: LocalDate): List<StoredPortfolioStatement> =
        Panache.withSession {
            list("entity = ?1 and statementDate = ?2 order by version", entity, date)
        }.awaitSuspending().map { withHoldings(it) }

    override suspend fun save(stored: StoredPortfolioStatement): StoredPortfolioStatement {
        val s = stored.snapshot
        val row = PortfolioStatementEntity().apply {
            statementUuid = stored.id
            idempotencyKey = stored.idempotencyKey
            entity = s.entity
            statementId = s.statementId
            safekeepingAccount = s.safekeepingAccount
            statementDate = s.statementDate
            currency = s.currency
            version = stored.version
            supersedes = stored.supersedes
            sha256 = stored.sha256
            uploadedBy = stored.uploadedBy
            uploadedAt = stored.uploadedAt
        }
        val holdings = s.positions.map { p ->
            PortfolioHoldingEntity().apply {
                statementUuid = stored.id
                entity = s.entity
                statementDate = s.statementDate
                isin = p.isin
                cfi = p.cfi
                instrumentClass = p.instrumentClass
                quantity = p.quantity
                valuation = p.valuation
                valuationCurrency = p.valuationCurrency
            }
        }
        try {
            Panache.withTransaction {
                // Retire the prior CURRENT version first: the partial unique index admits one
                // current row per (entity, date), and the superseded_by FK is deferred to commit.
                supersede(stored).flatMap { persist(row) }.flatMap {
                    if (holdings.isEmpty()) Uni.createFrom().voidItem() else holdingRepo.persist(holdings)
                }.flatMap { Panache.getSession().flatMap { it.flush() } }
                    .flatMap { insertKey(stored.idempotencyKey, stored.id) }
            }.awaitSuspending()
        } catch (e: ConstraintViolationException) {
            if (isDuplicate(e)) throw DuplicatePortfolioStatementException(e)
            throw e
        } catch (e: StalePriorVersionException) {
            throw DuplicatePortfolioStatementException(e)
        }
        return stored
    }

    /** Marks [StoredPortfolioStatement.supersedes] superseded; fails when it is no longer current. */
    private fun supersede(stored: StoredPortfolioStatement): Uni<Unit> {
        val prior = stored.supersedes ?: return Uni.createFrom().item(Unit)
        return update(
            "supersededBy = ?1, supersededAt = ?2 where statementUuid = ?3 and supersededBy is null",
            stored.id,
            stored.uploadedAt,
            prior,
        ).map { changed -> if (changed != 1) throw StalePriorVersionException() }
    }

    private class StalePriorVersionException : RuntimeException("prior portfolio version is no longer current")

    /**
     * Hibernate Reactive surfaces a lost insert race as [ConstraintViolationException] over a plain
     * [SQLException] (23505). Only the constraints meaning "this statement or this version is
     * already stored" qualify; any other violation keeps failing loudly.
     */
    private fun isDuplicate(e: Throwable): Boolean = generateSequence(e) { it.cause }
        .filterIsInstance<SQLException>()
        .any { ex ->
            (ex.sqlState == UNIQUE_VIOLATION || ex.message.orEmpty().contains("($UNIQUE_VIOLATION)")) &&
                DUPLICATE_CONSTRAINTS.any { ex.message.orEmpty().contains(it) }
        }

    private suspend fun withHoldings(row: PortfolioStatementEntity): StoredPortfolioStatement {
        val holdings = Panache.withSession {
            holdingRepo.list("statementUuid = ?1 order by isin", row.statementUuid)
        }.awaitSuspending()
        return StoredPortfolioStatement(
            id = row.statementUuid,
            idempotencyKey = row.idempotencyKey,
            version = row.version,
            supersedes = row.supersedes,
            supersededBy = row.supersededBy,
            supersededAt = row.supersededAt,
            sha256 = row.sha256,
            uploadedBy = row.uploadedBy,
            uploadedAt = row.uploadedAt,
            snapshot = PortfolioSnapshot(
                entity = row.entity,
                statementId = row.statementId,
                safekeepingAccount = row.safekeepingAccount,
                statementDate = row.statementDate,
                currency = row.currency,
                positions = holdings.map {
                    PortfolioPosition(
                        isin = it.isin,
                        cfi = it.cfi,
                        instrumentClass = it.instrumentClass,
                        quantity = it.quantity,
                        valuation = it.valuation,
                        valuationCurrency = it.valuationCurrency,
                    )
                },
            ),
        )
    }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        val DUPLICATE_CONSTRAINTS = listOf(
            "uq_portfolio_statement_idempotency_key",
            "portfolio_statement_keys_pkey",
            "uq_portfolio_statement_version",
            "uq_portfolio_statement_current",
        )
    }
}
