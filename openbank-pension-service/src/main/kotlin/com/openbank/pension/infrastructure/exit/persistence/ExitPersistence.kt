// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit.persistence

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.pension.application.exit.DeathClaimRepository
import com.openbank.pension.application.exit.ExitConcurrentUpdateException
import com.openbank.pension.application.exit.InstructionStatus
import com.openbank.pension.application.exit.PaymentInstruction
import com.openbank.pension.application.exit.PaymentInstructionRepository
import com.openbank.pension.application.exit.PayoutRequestRepository
import com.openbank.pension.application.exit.TerminationNoticeRepository
import com.openbank.pension.domain.exit.DeathClaim
import com.openbank.pension.domain.exit.DeathClaimStatus
import com.openbank.pension.domain.exit.PayoutRequest
import com.openbank.pension.domain.exit.PayoutStatus
import com.openbank.pension.domain.exit.TerminationNotice
import com.openbank.pension.domain.exit.TerminationStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Exit aggregates are stored as one row each: the columns the service QUERIES by (ids, status,
 * idempotency key) are real columns with constraints; the rest of the aggregate is a JSON body
 * written by a private, strict mapper. `@Version` makes two concurrent writers of one aggregate a
 * conflict instead of a lost update — a Temporal retry and a REST replay can race.
 *
 * Every column is named explicitly (entity-column-names): this service sets no naming strategy.
 */
@MappedSuperclass
abstract class ExitDocumentEntity : PanacheEntity() {
    @Column(name = "aggregate_id", nullable = false, unique = true)
    lateinit var aggregateId: UUID

    @Column(name = "contract_id", nullable = false)
    lateinit var contractId: UUID

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "idempotency_key")
    var idempotencyKey: String? = null

    @Column(name = "body", nullable = false)
    lateinit var body: String

    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Int = 0

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

@Entity
@Table(name = "pension_termination_notices")
class TerminationNoticeEntity : ExitDocumentEntity()

@Entity
@Table(name = "pension_payout_requests")
class PayoutRequestEntity : ExitDocumentEntity()

@Entity
@Table(name = "pension_death_claims")
class DeathClaimEntity : ExitDocumentEntity()

@Entity
@Table(name = "pension_payment_instructions")
class PaymentInstructionEntity : PanacheEntity() {
    @Column(name = "idempotency_key", nullable = false, unique = true)
    lateinit var idempotencyKey: String

    @Column(name = "contract_id", nullable = false)
    lateinit var contractId: UUID

    @Column(name = "purpose", nullable = false)
    lateinit var purpose: String

    @Column(name = "amount", nullable = false)
    lateinit var amount: BigDecimal

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "creditor_iban", nullable = false)
    lateinit var creditorIban: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "payment_ref")
    var paymentRef: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

internal object ExitJson {
    /** Fields only: derived getters (hashes, totals) are recomputed on read, never stored. */
    val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
        .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
        .setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
}

/**
 * Shared upsert with OPTIMISTIC LOCKING (ADR-0334 S8). An existing row is loaded and mutated
 * in-session, never re-persisted (#1521), and only when its `row_version` still equals the
 * [expectedVersion] the aggregate was read at — otherwise another writer got there first and this
 * write is refused rather than silently overwriting it (a confirmation racing an account change, an
 * activity racing an operator). Hibernate's `@Version` then guards the flush itself, so two
 * transactions that both pass the in-memory check cannot both commit. Returns the new version.
 */
internal suspend fun <E : ExitDocumentEntity> PanacheRepository<E>.upsert(
    id: UUID,
    contractId: UUID,
    status: String,
    idempotencyKey: String?,
    body: Any,
    updatedAt: Instant,
    expectedVersion: Int,
    create: () -> E,
): Int = Panache.withTransaction {
    // Every exit write takes the CONTRACT row lock first (#12376): a death claim registered
    // concurrently with a beneficiary change is serialised against it, so the change either sees
    // the claim (and is refused) or completes strictly before the claim is registered.
    lockContractRow(contractId).flatMap { find("aggregateId", id).firstResult() }.flatMap { existing ->
        if (existing != null && existing.rowVersion != expectedVersion) {
            throw ExitConcurrentUpdateException(
                "exit aggregate $id changed concurrently (version ${existing.rowVersion}, expected $expectedVersion)",
            )
        }
        val entity = existing ?: create().also { it.aggregateId = id }
        entity.contractId = contractId
        entity.status = status
        entity.idempotencyKey = idempotencyKey
        entity.body = ExitJson.mapper.writeValueAsString(body)
        entity.updatedAt = updatedAt
        val stored = if (existing == null) persist(entity) else Uni.createFrom().item(entity)
        stored.flatMap { Panache.getSession() }.flatMap { it.flush() }.map { entity.rowVersion }
    }
}.onFailure(::isLostRace).transform { e ->
    // Two writers that both passed the in-memory check race at FLUSH; Hibernate's @Version then
    // refuses the loser. That is the same lost race as above and must be retryable by the same
    // callers (PayoutService.onFreshRead), not an unrelated 500/409 (#12383 race review).
    // Translated after the transaction has rolled back so callers can re-read and reapply.
    ExitConcurrentUpdateException("exit aggregate $id changed concurrently at flush: ${e.javaClass.simpleName}")
        .also { it.initCause(e) }
}.awaitSuspending()

/** `SELECT … FOR UPDATE` on the contract row, inside the caller's transaction. */
internal fun lockContractRow(contractId: UUID): Uni<Any?> = Panache.getSession().flatMap { session ->
    session.createNativeQuery<Any>("select contract_id from pension_contracts where contract_id = :id for update")
        .setParameter("id", contractId)
        .resultList
        .map { rows -> rows.firstOrNull() }
}

/** An optimistic-lock failure raised at flush, possibly wrapped by the reactive session. */
internal fun isLostRace(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any {
    it is org.hibernate.StaleStateException || it is jakarta.persistence.OptimisticLockException
}

private const val MAX_CAUSE_DEPTH = 8

/** The stored body, with the row's current version stamped in so the next save can be checked. */
internal fun <T> ExitDocumentEntity.toDomain(type: Class<T>): T {
    val tree = ExitJson.mapper.readTree(body) as com.fasterxml.jackson.databind.node.ObjectNode
    tree.put("version", rowVersion)
    return ExitJson.mapper.treeToValue(tree, type)
}

internal suspend inline fun <E : ExitDocumentEntity, reified T> PanacheRepository<E>.load(
    query: String,
    vararg params: Any,
): List<T> = Panache.withSession { find(query, *params).list() }.awaitSuspending()
    .map { it.toDomain(T::class.java) }

/** Newest first, optionally in one status and/or contract, at most [limit] rows (operator queues, ADR-0334 S8). */
internal suspend inline fun <E : ExitDocumentEntity, reified T> PanacheRepository<E>.page(
    status: String?,
    contractId: UUID?,
    limit: Int,
): List<T> {
    val clauses = listOfNotNull(
        "status = :status".takeIf { status != null },
        "contractId = :contractId".takeIf { contractId != null },
    )
    val where = if (clauses.isEmpty()) "" else clauses.joinToString(" and ", postfix = " ")
    val params = io.quarkus.panache.common.Parameters()
    if (status != null) params.and("status", status)
    if (contractId != null) params.and("contractId", contractId)
    return Panache.withSession { find("${where}order by id desc", params).page(0, limit).list() }.awaitSuspending()
        .map { it.toDomain(T::class.java) }
}

@ApplicationScoped
class TerminationNoticeRepositoryImpl :
    TerminationNoticeRepository,
    PanacheRepository<TerminationNoticeEntity> {
    override suspend fun save(notice: TerminationNotice): TerminationNotice {
        val version = upsert(
            notice.id,
            notice.contractId,
            notice.status.name,
            notice.idempotencyKey,
            notice,
            notice.updatedAt,
            notice.version,
            ::TerminationNoticeEntity,
        )
        return notice.copy(version = version)
    }

    override suspend fun findById(id: UUID): TerminationNotice? =
        load<TerminationNoticeEntity, TerminationNotice>("aggregateId", id).firstOrNull()

    override suspend fun findOpenByContract(contractId: UUID): List<TerminationNotice> =
        load("contractId = ?1 and status in ?2", contractId, OPEN)

    private companion object {
        val OPEN = listOf(TerminationStatus.QUOTED.name, TerminationStatus.SIGNED.name)
    }
}

@ApplicationScoped
class PayoutRequestRepositoryImpl :
    PayoutRequestRepository,
    PanacheRepository<PayoutRequestEntity> {
    override suspend fun save(request: PayoutRequest): PayoutRequest {
        val version = upsert(
            request.id,
            request.contractId,
            request.status.name,
            request.idempotencyKey,
            request,
            request.updatedAt,
            request.version,
            ::PayoutRequestEntity,
        )
        return request.copy(version = version)
    }

    override suspend fun findById(id: UUID): PayoutRequest? =
        load<PayoutRequestEntity, PayoutRequest>("aggregateId", id).firstOrNull()

    override suspend fun findByContract(contractId: UUID): List<PayoutRequest> =
        load("contractId = ?1 order by id", contractId)

    override suspend fun list(status: PayoutStatus?, contractId: UUID?, limit: Int): List<PayoutRequest> =
        page<PayoutRequestEntity, PayoutRequest>(status?.name, contractId, limit)

    override suspend fun findInPayment(): List<PayoutRequest> =
        load("status in ?1", listOf(PayoutStatus.CONFIRMED.name, PayoutStatus.IN_PAYMENT.name))
}

@ApplicationScoped
class DeathClaimRepositoryImpl :
    DeathClaimRepository,
    PanacheRepository<DeathClaimEntity> {
    override suspend fun save(claim: DeathClaim): DeathClaim {
        val version = upsert(
            claim.id,
            claim.contractId,
            claim.status.name,
            claim.idempotencyKey,
            claim,
            claim.updatedAt,
            claim.version,
            ::DeathClaimEntity,
        )
        return claim.copy(version = version)
    }

    override suspend fun findById(id: UUID): DeathClaim? =
        load<DeathClaimEntity, DeathClaim>("aggregateId", id).firstOrNull()

    override suspend fun list(status: DeathClaimStatus?, limit: Int): List<DeathClaim> =
        page<DeathClaimEntity, DeathClaim>(status?.name, null, limit)

    override suspend fun findByContract(contractId: UUID): DeathClaim? =
        load<DeathClaimEntity, DeathClaim>("contractId", contractId).firstOrNull()
}

@ApplicationScoped
class PaymentInstructionRepositoryImpl(private val clock: Clock) :
    PaymentInstructionRepository,
    PanacheRepository<PaymentInstructionEntity> {

    override suspend fun recordIfAbsent(instruction: PaymentInstruction): PaymentInstruction {
        val stored = Panache.withTransaction {
            find("idempotencyKey", instruction.idempotencyKey).firstResult().flatMap { existing ->
                if (existing != null) {
                    Uni.createFrom().item(existing)
                } else {
                    val now = clock.instant()
                    persist(
                        PaymentInstructionEntity().apply {
                            idempotencyKey = instruction.idempotencyKey
                            contractId = instruction.contractId
                            purpose = instruction.purpose
                            amount = instruction.amount
                            currency = instruction.currency
                            creditorIban = instruction.creditorIban
                            status = instruction.status.name
                            createdAt = now
                            updatedAt = now
                        },
                    )
                }
            }
        }.awaitSuspending()
        return stored.toDomain()
    }

    override suspend fun markSent(idempotencyKey: String, paymentRef: String) {
        Panache.withTransaction {
            find("idempotencyKey", idempotencyKey).firstResult().map { row ->
                checkNotNull(row) { "instruction $idempotencyKey vanished" }
                if (row.paymentRef == null) {
                    row.paymentRef = paymentRef
                    row.status = InstructionStatus.SENT.name
                    row.updatedAt = clock.instant()
                }
            }
        }.awaitSuspending()
    }

    override suspend fun findByContract(contractId: UUID): List<PaymentInstruction> = Panache.withSession {
        find("contractId = ?1 order by id", contractId).list()
    }.awaitSuspending().map { it.toDomain() }

    private fun PaymentInstructionEntity.toDomain() = PaymentInstruction(
        idempotencyKey,
        contractId,
        purpose,
        amount,
        currency,
        creditorIban,
        InstructionStatus.valueOf(status),
        paymentRef,
    )
}
