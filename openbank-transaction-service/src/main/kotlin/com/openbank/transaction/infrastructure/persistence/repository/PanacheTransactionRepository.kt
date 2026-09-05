// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.application.usecase.TransactionUpdateConflictException
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.infrastructure.persistence.entity.TransactionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class PanacheTransactionRepository(private val outboxRepository: TransactionOutboxRepositoryImpl) :
    TransactionRepository,
    PanacheRepository<TransactionEntity> {

    override suspend fun findById(id: UUID): com.openbank.transaction.domain.model.Transaction? = Panache.withSession {
        find("id", id).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findByIdempotencyKey(key: String): com.openbank.transaction.domain.model.Transaction? =
        Panache.withSession {
            find("idempotencyKey", key).firstResult()
        }.awaitSuspending()?.toDomain()

    override suspend fun findByAccountId(
        accountId: UUID,
        limit: Int,
        afterId: UUID?,
    ): List<com.openbank.transaction.domain.model.Transaction> = Panache.withSession {
        find("sourceAccountId = ?1 or targetAccountId = ?1 order by initiatedAt desc", accountId)
            .range(0, limit - 1)
            .list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun save(
        transaction: com.openbank.transaction.domain.model.Transaction,
        outboxMessage: OutboxMessage,
    ): com.openbank.transaction.domain.model.Transaction = Panache.withTransaction {
        val entity = transaction.toEntity()
        persist(entity)
            .flatMap { outboxRepository.persistMessageUni(outboxMessage) }
            .replaceWith(transaction)
    }.awaitSuspending()

    override suspend fun update(
        transaction: com.openbank.transaction.domain.model.Transaction,
    ): com.openbank.transaction.domain.model.Transaction = translatingConflict(transaction) {
        Panache.withTransaction {
            versionMatched(transaction).replaceWith(transaction.copy(version = transaction.version + 1))
        }.awaitSuspending()
    }

    override suspend fun update(
        transaction: com.openbank.transaction.domain.model.Transaction,
        outboxMessage: OutboxMessage,
    ): com.openbank.transaction.domain.model.Transaction = translatingConflict(transaction) {
        Panache.withTransaction {
            versionMatched(transaction)
                .flatMap { outboxRepository.persistMessageUni(outboxMessage) }
                .replaceWith(transaction.copy(version = transaction.version + 1))
        }.awaitSuspending()
    }

    /**
     * The most recent non-blank transaction descriptions, newest first, capped at [limit] (#8573).
     *
     * Feeds the operator's unmatched-descriptor worklist. Deliberately a bounded window and not a
     * full scan: normalisation lives in Kotlin, so the anti-join against the catalogue cannot run
     * in SQL, and an operator wants the frequent ones rather than an exhaustive answer. Returns raw
     * acquirer descriptors — the caller normalises.
     */
    suspend fun recentDescriptions(limit: Int): List<String> = Panache.withSession {
        find("description is not null and description <> '' order by bookingDate desc, initiatedAt desc")
            .page(0, limit)
            .list()
    }.awaitSuspending().mapNotNull { it.description }

    override suspend fun countStuckSagas(olderThan: Instant): Long = Panache.withSession {
        count(
            "status in ?1 and initiatedAt <= ?2",
            NON_TERMINAL_STATUSES,
            olderThan,
        )
    }.awaitSuspending()

    // Truly simultaneous writers both pass the version-matched read before either commits; the
    // loser's flush then fails the @Version check (0 rows). Same conflict, same 409 (#465).
    private suspend fun translatingConflict(
        transaction: com.openbank.transaction.domain.model.Transaction,
        block: suspend () -> com.openbank.transaction.domain.model.Transaction,
    ): com.openbank.transaction.domain.model.Transaction = try {
        block()
    } catch (e: jakarta.persistence.OptimisticLockException) {
        throw TransactionUpdateConflictException(
            "Transaction ${transaction.id} was modified concurrently (flush-time version check)",
            e,
        )
    } catch (e: org.hibernate.StaleObjectStateException) {
        throw TransactionUpdateConflictException(
            "Transaction ${transaction.id} was modified concurrently (flush-time version check)",
            e,
        )
    }

    // Optimistic guard (#465): TransactionEntity.version is a plain column (no @Version), so the
    // old read-copy-write update was pure last-write-wins — two racing reversals both read
    // COMPLETED, both flipped it, and BOTH initiated a reversal credit (double refund). Matching
    // on the version the caller's domain object was read at makes the loser fail with a clean
    // conflict before it can act on the stale state; the version increments here, not in the
    // domain (transitions are status-only copies).
    private fun versionMatched(transaction: com.openbank.transaction.domain.model.Transaction) =
        find("id = ?1 and version = ?2", transaction.id, transaction.version).firstResult()
            .invoke { entity ->
                if (entity == null) {
                    throw TransactionUpdateConflictException(
                        "Transaction ${transaction.id} was modified concurrently " +
                            "(expected version ${transaction.version})",
                    )
                }
                entity.apply {
                    status = transaction.status.name
                    completedAt = transaction.completedAt
                    failedAt = transaction.failedAt
                    failureReason = transaction.failureReason
                    // version is @Version-managed: Hibernate bumps it at flush and guards the
                    // UPDATE with WHERE version = <read version> — never set it manually.
                }
            }

    // Extended search — BIAN aligned
    suspend fun search(query: TransactionSearchQuery): List<com.openbank.transaction.domain.model.Transaction> {
        val conditions = query.toSearchConditions()
        val where = if (conditions.isEmpty()) {
            "order by initiatedAt desc"
        } else {
            conditions.joinToString(" and ") { it.clause } + " order by initiatedAt desc"
        }
        val params = conditions.flatMap { it.params }
        return Panache.withSession {
            find(where, *params.toTypedArray())
                .range(query.offset, query.offset + query.limit - 1)
                .list()
        }.awaitSuspending().map { it.toDomain() }
    }

    private companion object {
        /**
         * The saga is still in flight in exactly these two states; COMPLETED / FAILED / REVERSED
         * are terminal. Stored as the enum *names* because `TransactionEntity.status` is a String
         * column.
         */
        val NON_TERMINAL_STATUSES = listOf(TransactionStatus.PENDING.name, TransactionStatus.PROCESSING.name)
    }
}

private data class SearchCondition(val clause: String, val params: List<Any>)

private fun TransactionSearchQuery.toSearchConditions(): List<SearchCondition> {
    val result = mutableListOf<SearchCondition>()
    var i = 1
    accountId?.let {
        result += SearchCondition("(sourceAccountId = ?${i++} or targetAccountId = ?${i++})", listOf(it, it))
    }
    iban?.let {
        result += SearchCondition("(sourceIban = ?${i++} or targetIban = ?${i++})", listOf(it, it))
    }
    bban?.let {
        result += SearchCondition("(sourceBban = ?${i++} or targetBban = ?${i++})", listOf(it, it))
    }
    referenceNumber?.let { result += SearchCondition("referenceNumber = ?${i++}", listOf(it)) }
    endToEndId?.let { result += SearchCondition("endToEndId = ?${i++}", listOf(it)) }
    counterpartyName?.let {
        result += SearchCondition("lower(counterpartyName) like ?${i++}", listOf("%${it.lowercase()}%"))
    }
    status?.let { result += SearchCondition("status = ?${i++}", listOf(it.name)) }
    type?.let { result += SearchCondition("type = ?${i++}", listOf(it.name)) }
    dateFrom?.let { result += SearchCondition("bookingDate >= ?${i++}", listOf(it)) }
    dateTo?.let { result += SearchCondition("bookingDate <= ?${i++}", listOf(it)) }
    amountMin?.let { result += SearchCondition("amount >= ?${i++}", listOf(it)) }
    amountMax?.let { result += SearchCondition("amount <= ?${i++}", listOf(it)) }
    return result
}

data class TransactionSearchQuery(
    val accountId: UUID? = null,
    val iban: String? = null,
    val bban: String? = null,
    val referenceNumber: String? = null,
    val endToEndId: String? = null,
    val counterpartyName: String? = null,
    val status: TransactionStatus? = null,
    val type: TransactionType? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val amountMin: BigDecimal? = null,
    val amountMax: BigDecimal? = null,
    val limit: Int = 50,
    val offset: Int = 0,
)

private fun TransactionEntity.toDomain(): com.openbank.transaction.domain.model.Transaction {
    val currency = CurrencyCode(currencyCode)
    val baseCurrency = CurrencyCode(baseCurrencyCode)
    return com.openbank.transaction.domain.model.Transaction(
        id = id,
        referenceNumber = referenceNumber,
        type = TransactionType.valueOf(type),
        sourceAccountId = sourceAccountId,
        targetAccountId = targetAccountId,
        amount = Money(amount.setScale(currency.defaultFractionDigits, RoundingMode.HALF_EVEN), currency),
        fxRate = fxRate,
        baseAmount = Money(
            baseAmount.setScale(baseCurrency.defaultFractionDigits, RoundingMode.HALF_EVEN),
            baseCurrency,
        ),
        status = TransactionStatus.valueOf(status),
        description = description,
        valueDate = valueDate,
        bookingDate = bookingDate,
        initiatedAt = initiatedAt,
        completedAt = completedAt,
        failedAt = failedAt,
        failureReason = failureReason,
        idempotencyKey = idempotencyKey,
        version = version,
        initiatedByPartyId = actorId
            ?.takeIf { actorType == "CUSTOMER" }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        scaChallengeId = scaChallengeId,
        scaExemption = scaExemption,
        rail = rail?.let { runCatching { com.openbank.libs.domain.payment.PaymentRail.valueOf(it) }.getOrNull() },
        instructionType = instructionType
            ?.let { runCatching { com.openbank.libs.domain.payment.InstructionType.valueOf(it) }.getOrNull() },
        merchantCategory = merchantCategory,
        originatingPaymentId = originatingPaymentId,
    )
}

private fun com.openbank.transaction.domain.model.Transaction.toEntity() = TransactionEntity().apply {
    id = this@toEntity.id
    referenceNumber = this@toEntity.referenceNumber
    type = this@toEntity.type.name
    sourceAccountId = this@toEntity.sourceAccountId
    targetAccountId = this@toEntity.targetAccountId
    amount = this@toEntity.amount.amount
    currencyCode = this@toEntity.amount.currency.code
    fxRate = this@toEntity.fxRate
    baseAmount = this@toEntity.baseAmount.amount
    baseCurrencyCode = this@toEntity.baseAmount.currency.code
    status = this@toEntity.status.name
    description = this@toEntity.description
    valueDate = this@toEntity.valueDate
    bookingDate = this@toEntity.bookingDate
    initiatedAt = this@toEntity.initiatedAt
    completedAt = this@toEntity.completedAt
    failedAt = this@toEntity.failedAt
    failureReason = this@toEntity.failureReason
    idempotencyKey = this@toEntity.idempotencyKey
    version = this@toEntity.version
    // Customer identity lands in the (previously dormant) V2 compliance columns.
    this@toEntity.initiatedByPartyId?.let {
        actorId = it.toString()
        actorType = "CUSTOMER"
    }
    scaChallengeId = this@toEntity.scaChallengeId
    scaExemption = this@toEntity.scaExemption
    rail = this@toEntity.rail?.name
    instructionType = this@toEntity.instructionType?.name
    merchantCategory = this@toEntity.merchantCategory
    originatingPaymentId = this@toEntity.originatingPaymentId
}
