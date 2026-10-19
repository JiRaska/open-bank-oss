// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.payment

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.treasury.application.port.out.CashFlowConflictException
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.PaymentRepository
import com.openbank.treasury.application.port.out.TreasuryOutboxRepository
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.FlowDirection
import com.openbank.treasury.domain.payment.Payment
import com.openbank.treasury.domain.payment.PaymentStatus
import com.openbank.treasury.domain.payment.SsiSnapshot
import com.openbank.treasury.domain.reference.SettlementRail
import com.openbank.treasury.infrastructure.cashflow.DealLookupPanacheRepository
import com.openbank.treasury.infrastructure.cashflow.toDomain
import com.openbank.treasury.infrastructure.persistence.repository.CashFlowPanacheRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "treasury_payments")
class PaymentEntity : PanacheEntityBase {
    @Id
    @Column(name = "payment_id", nullable = false)
    lateinit var paymentId: UUID

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "direction", nullable = false)
    lateinit var direction: String

    @Column(name = "counterparty_id", nullable = false)
    lateinit var counterpartyId: String

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "amount", nullable = false)
    lateinit var amount: BigDecimal

    @Column(name = "value_date", nullable = false)
    lateinit var valueDate: LocalDate

    @Column(name = "rail")
    var rail: String? = null

    @Column(name = "beneficiary_account")
    var beneficiaryAccount: String? = null

    @Column(name = "bic")
    var bic: String? = null

    @Column(name = "ssi_version")
    var ssiVersion: Int? = null

    @Column(name = "deal_ids", nullable = false)
    lateinit var dealIds: String

    @Column(name = "term_authors", nullable = false)
    lateinit var termAuthors: String

    @Column(name = "idempotency_key", nullable = false)
    lateinit var idempotencyKey: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "rail_reference")
    var railReference: String? = null

    @Column(name = "released_by")
    var releasedBy: String? = null

    @Column(name = "released_at")
    var releasedAt: Instant? = null

    @Column(name = "status_reason")
    var statusReason: String? = null
}

@Entity
@Table(name = "treasury_payment_flows")
class PaymentFlowEntity : PanacheEntityBase {
    @Id
    @Column(name = "flow_id", nullable = false)
    lateinit var flowId: UUID

    @Column(name = "payment_id", nullable = false)
    lateinit var paymentId: UUID

    @Column(name = "deal_id", nullable = false)
    lateinit var dealId: UUID

    @Column(name = "leg_index", nullable = false)
    var legIndex: Int = 0

    @Column(name = "period_index", nullable = false)
    var periodIndex: Int = 0

    @Column(name = "kind", nullable = false)
    lateinit var kind: String
}

@ApplicationScoped
class PaymentPanacheRepository : PanacheRepositoryBase<PaymentEntity, UUID>

@ApplicationScoped
class PaymentFlowPanacheRepository : PanacheRepositoryBase<PaymentFlowEntity, UUID>

@ApplicationScoped
class PaymentRepositoryImpl(
    private val payments: PaymentPanacheRepository,
    private val links: PaymentFlowPanacheRepository,
    private val flows: CashFlowPanacheRepository,
    private val deals: DealLookupPanacheRepository,
    private val outbox: TreasuryOutboxRepository,
    private val clock: Clock,
) : PaymentRepository {

    override suspend fun save(payment: Payment, prior: PaymentStatus?, event: DealEvent?, settleFlows: Boolean) {
        Panache.withTransaction {
            val write: Uni<Void> = if (prior == null) {
                insert(payment)
            } else {
                payments.find("paymentId", payment.paymentId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
                    .flatMap { row ->
                        checkNotNull(row) { "payment ${payment.paymentId} disappeared" }
                        check(row.status == prior.name) {
                            "payment ${payment.paymentId} is now ${row.status}, not $prior; reload and retry"
                        }
                        row.fill(payment)
                        when {
                            payment.status == PaymentStatus.CANCELLED -> release(payment)
                            settleFlows -> flows.update(
                                "state = 'SETTLED', rowVersion = rowVersion + 1 where flowId in ?1 and paymentId = ?2",
                                payment.nettedFlowIds,
                                payment.paymentId.toString(),
                            ).replaceWithVoid()
                            else -> Uni.createFrom().voidItem()
                        }
                    }
            }
            write.flatMap {
                event?.let {
                    outbox.persistInTransaction(
                        OutboxMessage(
                            aggregateId = payment.paymentId,
                            eventType = it.eventType,
                            payload = it.payload,
                            createdAt = clock.instant(),
                        ),
                    )
                } ?: Uni.createFrom().voidItem()
            }
        }.awaitSuspending()
    }

    /**
     * A PROPOSED payment, its links and the flow pointers. Each flow is claimed by its OWN guarded
     * UPDATE (still FIXED, no payment yet) that must hit exactly one row; the link table's primary key
     * (flow) and unique slot (deal, leg, period, kind — across every schedule version) refuse a second
     * payment for the same flow even after a regeneration. Any miss refuses the whole insert.
     */
    private fun insert(payment: Payment): Uni<Void> =
        flows.find("flowId in ?1", payment.nettedFlowIds).list().flatMap { rows ->
            check(rows.size == payment.nettedFlowIds.size) { "a flow of payment ${payment.paymentId} disappeared" }
            payments.persist(PaymentEntity().fill(payment)).flatMap {
                links.persist(
                    rows.map { r ->
                        PaymentFlowEntity().apply {
                            flowId = r.flowId
                            paymentId = payment.paymentId
                            dealId = r.dealId
                            legIndex = r.legIndex
                            periodIndex = r.periodIndex
                            kind = r.kind
                        }
                    },
                )
            }.flatMap {
                payment.nettedFlowIds.fold(Uni.createFrom().voidItem()) { acc, id ->
                    acc.flatMap {
                        flows.update(
                            "paymentId = ?1, rowVersion = rowVersion + 1 where flowId = ?2 and paymentId is null and state = 'FIXED'",
                            payment.paymentId.toString(),
                            id,
                        ).map { n ->
                            if (n !=
                                1
                            ) {
                                throw CashFlowConflictException(
                                    "flow $id is already on a payment or no longer FIXED",
                                )
                            }
                        }.replaceWithVoid()
                    }
                }
            }
        }

    /** A cancelled payment frees its flows for a new proposal. */
    /**
     * A cancelled PROPOSED payment frees exactly its own flows for a new proposal: scoped to this
     * payment's id, version-bumped, and every one must be freed or the cancel is refused.
     */
    private fun release(payment: Payment): Uni<Void> {
        check(payment.status == PaymentStatus.CANCELLED) { "only a cancelled payment frees its flows" }
        return flows.update(
            "paymentId = null, rowVersion = rowVersion + 1 where flowId in ?1 and paymentId = ?2",
            payment.nettedFlowIds,
            payment.paymentId.toString(),
        ).flatMap { n ->
            if (n != payment.nettedFlowIds.size) {
                throw CashFlowConflictException("payment ${payment.paymentId} no longer holds all its flows")
            }
            links.delete("paymentId", payment.paymentId)
        }.replaceWithVoid()
    }

    override suspend fun saveRelease(
        payment: Payment,
        event: DealEvent,
        fundingKey: String,
        fundingCheck: (pendingOut: BigDecimal) -> Unit,
    ) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Int>("select 1 from (select pg_advisory_xact_lock(hashtext(:k))) l")
                    .setParameter("k", fundingKey).singleResult
            }.flatMap {
                payments.find(
                    "direction = 'OUT' and currency = ?1 and valueDate = ?2 and status in ?3",
                    payment.currency,
                    payment.valueDate,
                    UNSETTLED_OUT,
                ).list()
            }.flatMap { pending ->
                fundingCheck(pending.sumOf { it.amount })
                payments.find("paymentId", payment.paymentId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
            }.flatMap { row ->
                checkNotNull(row) { "payment ${payment.paymentId} disappeared" }
                check(row.status == PaymentStatus.PROPOSED.name) {
                    "payment ${payment.paymentId} is now ${row.status}, not PROPOSED"
                }
                row.fill(payment)
                outbox.persistInTransaction(
                    OutboxMessage(
                        aggregateId = payment.paymentId,
                        eventType = event.eventType,
                        payload = event.payload,
                        createdAt = clock.instant(),
                    ),
                )
            }
        }.awaitSuspending()
    }

    override suspend fun find(paymentId: UUID): Payment? = Panache.withSession {
        payments.find("paymentId", paymentId).firstResult()
    }.awaitSuspending()?.let { withFlows(listOf(it)).single() }

    override suspend fun findByKey(idempotencyKey: String): Payment? = Panache.withSession {
        payments.find("idempotencyKey", idempotencyKey).firstResult()
    }.awaitSuspending()?.let { withFlows(listOf(it)).single() }

    override suspend fun list(status: PaymentStatus?, from: LocalDate, to: LocalDate, limit: Int): List<Payment> {
        val rows = Panache.withSession {
            if (status == null) {
                payments.find("valueDate between ?1 and ?2 order by valueDate asc, createdAt asc", from, to)
            } else {
                payments.find(
                    "status = ?1 and valueDate between ?2 and ?3 order by valueDate asc, createdAt asc",
                    status.name,
                    from,
                    to,
                )
            }.page(0, limit).list()
        }.awaitSuspending()
        return withFlows(rows)
    }

    override suspend fun pendingOut(currency: String, valueDate: LocalDate): BigDecimal = Panache.withSession {
        payments.find(
            "direction = 'OUT' and currency = ?1 and valueDate = ?2 and status in ?3",
            currency,
            valueDate,
            UNSETTLED_OUT,
        ).list()
    }.awaitSuspending().sumOf { it.amount }

    override suspend fun expectedIn(currency: String, amount: BigDecimal, valueDate: LocalDate): List<Payment> {
        val rows = Panache.withSession {
            payments.find(
                "direction = 'IN' and status = 'PROPOSED' and currency = ?1 and amount = ?2 and valueDate = ?3",
                currency,
                amount,
                valueDate,
            ).list()
        }.awaitSuspending()
        return withFlows(rows)
    }

    override suspend fun unpaidFixedFlows(from: LocalDate, to: LocalDate): List<CashFlow> {
        val rows = Panache.withSession {
            flows.find(
                "state = 'FIXED' and paymentId is null and paymentDate between ?1 and ?2",
                from,
                to,
            ).page(0, MAX_FLOWS).list()
        }.awaitSuspending()
        if (rows.isEmpty()) return emptyList()
        val live = Panache.withSession {
            deals.find("dealId in ?1 and kind = 'ACTUAL'", rows.map { it.dealId }.distinct()).list()
        }.awaitSuspending().filter { it.state in PAYABLE_STATES }.associate { it.dealId to it.scheduleVersion }
        val current = rows.filter { live[it.dealId] == it.scheduleVersion }
        if (current.isEmpty()) return emptyList()
        // A slot already on a (non-cancelled) payment under ANY schedule version is paid: a
        // regeneration's new row for it must never be proposed again.
        val paidSlots = Panache.withSession {
            links.find("dealId in ?1", current.map { it.dealId }.distinct()).list()
        }.awaitSuspending().map { listOf(it.dealId, it.legIndex, it.periodIndex, it.kind) }.toSet()
        return current.filter {
            listOf(it.dealId, it.legIndex, it.periodIndex, it.kind) !in paidSlots
        }.map { it.toDomain() }
    }

    private suspend fun withFlows(rows: List<PaymentEntity>): List<Payment> {
        if (rows.isEmpty()) return emptyList()
        val byPayment = Panache.withSession {
            links.find("paymentId in ?1", rows.map { it.paymentId }).list()
        }.awaitSuspending().groupBy({ it.paymentId }, { it.flowId })
        return rows.map { it.toDomain(byPayment[it.paymentId].orEmpty()) }
    }

    private companion object {
        const val MAX_FLOWS = 10_000

        /** Released and not yet settled: still owed out of the funding account. */
        val UNSETTLED_OUT = listOf("RELEASED", "SENT", "RAIL_NOT_SUPPORTED")

        /** A deal pays only once it is booked; a draft, cancelled or reversed deal never does. */
        val PAYABLE_STATES = setOf("BOOKED", "CONFIRMED", "SETTLED")
    }
}

private fun PaymentEntity.fill(p: Payment) = apply {
    paymentId = p.paymentId
    status = p.status.name
    direction = p.direction.name
    counterpartyId = p.counterpartyId
    currency = p.currency
    amount = p.amount
    valueDate = p.valueDate
    rail = p.ssi?.rail?.name
    beneficiaryAccount = p.ssi?.beneficiaryAccount
    bic = p.ssi?.bic
    ssiVersion = p.ssi?.ssiVersion
    dealIds = p.dealIds.joinToString(",")
    termAuthors = p.termAuthors.sorted().joinToString("\n")
    idempotencyKey = p.idempotencyKey
    createdAt = p.createdAt
    railReference = p.railReference
    releasedBy = p.releasedBy
    releasedAt = p.releasedAt
    statusReason = p.statusReason
}

private fun PaymentEntity.toDomain(flowIds: List<UUID>) = Payment(
    paymentId = paymentId,
    status = PaymentStatus.valueOf(status),
    direction = FlowDirection.valueOf(direction),
    counterpartyId = counterpartyId,
    currency = currency,
    amount = amount,
    valueDate = valueDate,
    ssi = rail?.let { SsiSnapshot(SettlementRail.valueOf(it), checkNotNull(beneficiaryAccount), bic, ssiVersion ?: 0) },
    nettedFlowIds = flowIds.sorted(),
    dealIds = dealIds.split(',').filter(String::isNotBlank).map(UUID::fromString),
    termAuthors = termAuthors.split('\n').filter(String::isNotBlank).toSet(),
    idempotencyKey = idempotencyKey,
    createdAt = createdAt,
    railReference = railReference,
    releasedBy = releasedBy,
    releasedAt = releasedAt,
    statusReason = statusReason,
)
