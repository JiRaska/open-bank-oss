// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.nostro

import com.openbank.treasury.application.port.out.DuplicateStatementException
import com.openbank.treasury.application.port.out.NostroStatementRepository
import com.openbank.treasury.application.port.out.StoredStatement
import com.openbank.treasury.domain.model.NostroStatement
import com.openbank.treasury.domain.model.StatementDirection
import com.openbank.treasury.domain.model.StatementEntry
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.exception.ConstraintViolationException
import java.sql.SQLException
import java.util.UUID

@ApplicationScoped
class NostroStatementEntryPanacheRepository : PanacheRepository<NostroStatementEntryEntity>

@ApplicationScoped
open class NostroStatementRepositoryImpl(private val entryRepo: NostroStatementEntryPanacheRepository) :
    NostroStatementRepository,
    PanacheRepository<NostroStatementEntity> {

    override suspend fun findById(id: UUID): StoredStatement? = load("statementUuid", id)

    override suspend fun findByIdempotencyKey(key: String): StoredStatement? = load("idempotencyKey", key)

    override suspend fun findByAccountAndStatementId(iban: String, statementId: String): StoredStatement? =
        Panache.withSession {
            find("iban = ?1 and statementId = ?2", iban, statementId).firstResult()
        }.awaitSuspending()?.let { withEntries(it) }

    override suspend fun save(stored: StoredStatement): StoredStatement {
        val s = stored.statement
        val row = NostroStatementEntity().apply {
            statementUuid = stored.id
            idempotencyKey = stored.idempotencyKey
            statementId = s.statementId
            iban = s.iban
            glCode = stored.glCode
            currency = s.currency
            statementDate = s.statementDate
            openingBalance = s.openingBalance
            closingBalance = s.closingBalance
            sha256 = stored.sha256
            uploadedBy = stored.uploadedBy
            uploadedAt = stored.uploadedAt
        }
        val entries = s.entries.map { e ->
            NostroStatementEntryEntity().apply {
                statementUuid = stored.id
                sequence = e.sequence
                amount = e.amount
                currency = e.currency
                direction = e.direction.name
                bookingDate = e.bookingDate
                reference = e.reference
            }
        }
        try {
            Panache.withTransaction {
                persist(row).flatMap {
                    if (entries.isEmpty()) Uni.createFrom().voidItem() else entryRepo.persist(entries)
                }
            }.awaitSuspending()
        } catch (e: ConstraintViolationException) {
            if (isDuplicate(e)) throw DuplicateStatementException(e)
            throw e
        }
        return stored
    }

    /**
     * Hibernate Reactive surfaces a lost insert race as [ConstraintViolationException] over a plain
     * [SQLException] (23505, the server message naming the constraint) — never pgjdbc's type. Only
     * the two constraints that mean "this statement is already stored" qualify; any other unique
     * violation keeps failing loudly.
     */
    private fun isDuplicate(e: Throwable): Boolean = generateSequence(e) { it.cause }
        .filterIsInstance<SQLException>()
        .any { ex ->
            (ex.sqlState == UNIQUE_VIOLATION || ex.message.orEmpty().contains("($UNIQUE_VIOLATION)")) &&
                DUPLICATE_CONSTRAINTS.any { ex.message.orEmpty().contains(it) }
        }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        val DUPLICATE_CONSTRAINTS = listOf("uq_nostro_statement_idempotency_key", "uq_nostro_statement_account")
    }

    private suspend fun load(field: String, value: Any): StoredStatement? =
        Panache.withSession { find(field, value).firstResult() }.awaitSuspending()?.let { withEntries(it) }

    private suspend fun withEntries(row: NostroStatementEntity): StoredStatement {
        val entries = Panache.withSession {
            entryRepo.list("statementUuid = ?1 order by sequence", row.statementUuid)
        }.awaitSuspending()
        return StoredStatement(
            id = row.statementUuid,
            idempotencyKey = row.idempotencyKey,
            glCode = row.glCode,
            sha256 = row.sha256,
            uploadedBy = row.uploadedBy,
            uploadedAt = row.uploadedAt,
            statement = NostroStatement(
                statementId = row.statementId,
                iban = row.iban,
                currency = row.currency,
                statementDate = row.statementDate,
                openingBalance = row.openingBalance,
                closingBalance = row.closingBalance,
                entries = entries.map {
                    StatementEntry(
                        sequence = it.sequence,
                        amount = it.amount,
                        currency = it.currency,
                        direction = StatementDirection.valueOf(it.direction),
                        bookingDate = it.bookingDate,
                        reference = it.reference,
                    )
                },
            ),
        )
    }
}
