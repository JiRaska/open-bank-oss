// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pensionfund.application.port.ClassificationReceipt
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.AllocationTarget
import com.openbank.pensionfund.domain.model.ClassificationCorrectionStatus
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.FundStrategy
import com.openbank.pensionfund.domain.model.GlidePathStep
import com.openbank.pensionfund.domain.model.InstrumentClass
import com.openbank.pensionfund.domain.model.NavFigures
import com.openbank.pensionfund.domain.model.NavPosition
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.OrderType
import com.openbank.pensionfund.domain.model.PositionClassificationCorrection
import com.openbank.pensionfund.domain.model.Precision
import com.openbank.pensionfund.domain.model.StrategyChange
import com.openbank.pensionfund.domain.model.StrategyChangeStatus
import com.openbank.pensionfund.domain.model.StrategyStatus
import com.openbank.pensionfund.domain.model.UnitHolding
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.pensionfund.domain.model.UnitTransaction
import com.openbank.pensionfund.domain.model.UnitTransactionType
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
@Suppress("TooManyFunctions", "LongParameterList")
class PanachePensionFundStore(
    private val funds: FundRepository,
    private val strategies: FundStrategyRepository,
    private val changes: StrategyChangeRepository,
    private val navs: FundNavRepository,
    private val orders: UnitOrderRepository,
    private val holdings: UnitHoldingRepository,
    private val transactions: UnitTransactionRepository,
    private val positions: FundNavPositionRepository,
    private val classifications: PositionClassificationCorrectionRepository,
    private val receipts: ClassificationReceiptRepository,
) : PensionFundStore {

    private val json = jacksonObjectMapper().findAndRegisterModules()

    /**
     * One transaction, written in dependency order with a flush after each NAV: a correction's
     * publication supersedes the original and publishes the replacement, and the partial unique
     * index on (fund, date, PUBLISHED) must never see both at once.
     */
    override suspend fun commit(changes: StoreChanges) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                val writes = mutableListOf<() -> Uni<*>>()
                // Insert-only, first: a competing request cannot apply its mutation after this key wins.
                changes.classificationReceipt?.let { receipt ->
                    writes += {
                        session.persist(
                            ClassificationReceiptEntity().also {
                                it.key = receipt.key
                                it.fingerprint = receipt.fingerprint
                                it.responseSnapshot = json.writeValueAsString(receipt.result)
                            },
                        ).flatMap { session.flush() }
                    }
                }
                changes.funds.forEach { f -> writes += { session.merge(f.toEntity()) } }
                changes.strategies.forEach { s -> writes += { session.merge(s.toEntity()) } }
                changes.strategyChanges.forEach { c -> writes += { session.merge(c.toEntity()) } }
                changes.navs.forEach { n -> writes += { session.merge(n.toEntity()).flatMap { session.flush() } } }
                changes.orders.forEach { o -> writes += { session.merge(o.toEntity()).flatMap { session.flush() } } }
                changes.holdings.forEach { h -> writes += { session.merge(h.toEntity()) } }
                changes.transactions.forEach { t -> writes += { session.merge(t.toEntity()) } }
                changes.navPositions.forEach { p -> writes += { session.persist(p.toEntity()) } }
                changes.classificationCorrections.forEach { c ->
                    writes += {
                        if (c.status == ClassificationCorrectionStatus.PROPOSED) {
                            session.persist(c.toEntity())
                        } else {
                            session.createQuery<Int>(
                                "update PositionClassificationCorrectionEntity set status = :status, " +
                                    "decidedBy = :actor, decidedAt = :at where id = :id and status = 'PROPOSED'",
                            )
                                .setParameter("status", c.status.name)
                                .setParameter("actor", c.decidedBy)
                                .setParameter("at", c.decidedAt)
                                .setParameter("id", c.id)
                                .executeUpdate()
                                .invoke { count -> check(count == 1) { "correction ${c.id} was already decided" } }
                        }
                    }
                }
                writes.fold(Uni.createFrom().voidItem() as Uni<*>) { acc, w -> acc.flatMap { w() } }
                    .flatMap { session.flush() }
            }
        }.awaitSuspending()
    }

    override suspend fun classificationReceipt(key: String): ClassificationReceipt? =
        read { receipts.findById(key) }?.let {
            ClassificationReceipt(it.key, it.fingerprint, json.readValue(it.responseSnapshot))
        }

    private suspend fun <T> read(block: () -> Uni<T>): T = Panache.withSession { block() }.awaitSuspending()

    override suspend fun fund(id: UUID): Fund? = read { funds.findById(id) }?.toDomain()

    override suspend fun funds(): List<Fund> = read { funds.find("order by createdAt").list() }.map { it.toDomain() }

    override suspend fun strategy(id: UUID): FundStrategy? = read { strategies.findById(id) }?.toDomain()

    override suspend fun strategies(): List<FundStrategy> =
        read { strategies.find("order by createdAt").list() }.map { it.toDomain() }

    override suspend fun strategyChange(id: UUID): StrategyChange? = read { changes.findById(id) }?.toDomain()

    override suspend fun strategyChanges(strategyId: UUID): List<StrategyChange> =
        read { changes.find("strategyId = ?1 order by submittedAt desc", strategyId).list() }.map { it.toDomain() }

    override suspend fun nav(id: UUID): NavRecord? = read { navs.findById(id) }?.toDomain()

    override suspend fun navs(fundId: UUID): List<NavRecord> = read {
        navs.find("fundId = ?1 order by valuationDate desc, calculatedAt desc", fundId).list()
    }.map { it.toDomain() }

    override suspend fun latestPublishedNav(fundId: UUID): NavRecord? = read {
        navs.find(
            "fundId = ?1 and status = ?2 order by valuationDate desc",
            fundId,
            NavStatus.PUBLISHED.name,
        ).firstResult()
    }?.toDomain()

    override suspend fun publishedNav(fundId: UUID, valuationDate: LocalDate): NavRecord? = read {
        navs.find(
            "fundId = ?1 and valuationDate = ?2 and status = ?3",
            fundId,
            valuationDate,
            NavStatus.PUBLISHED.name,
        ).firstResult()
    }?.toDomain()

    override suspend fun pendingOrders(fundId: UUID): List<UnitOrder> = read {
        orders.find("fundId = ?1 and status = ?2 order by placedAt", fundId, OrderStatus.PENDING.name).list()
    }.map { it.toDomain() }

    override suspend fun pendingOrdersForContract(contractId: UUID): List<UnitOrder> = read {
        orders.find("contractId = ?1 and status = ?2 order by placedAt", contractId, OrderStatus.PENDING.name).list()
    }.map { it.toDomain() }

    override suspend fun orders(contractId: UUID): List<UnitOrder> =
        read { orders.find("contractId = ?1 order by placedAt desc", contractId).list() }.map { it.toDomain() }

    override suspend fun orderByIdempotencyKey(contractId: UUID, idempotencyKey: String): UnitOrder? = read {
        orders.find("contractId = ?1 and idempotencyKey = ?2", contractId, idempotencyKey).firstResult()
    }?.toDomain()

    override suspend fun holding(contractId: UUID, fundId: UUID): UnitHolding? =
        read { holdings.findById(holdingId(contractId, fundId)) }?.toDomain()

    override suspend fun holdings(contractId: UUID): List<UnitHolding> =
        read { holdings.find("contractId = ?1", contractId).list() }.map { it.toDomain() }

    override suspend fun unitsOutstanding(fundId: UUID): BigDecimal =
        read { holdings.find("fundId = ?1", fundId).list() }
            .fold(BigDecimal.ZERO.setScale(Precision.UNIT_SCALE)) { acc, h -> acc + h.units }

    override suspend fun transactionsPricedAt(navId: UUID): List<UnitTransaction> =
        read { transactions.find("navId = ?1", navId).list() }.map { it.toDomain() }

    override suspend fun transactions(contractId: UUID): List<UnitTransaction> =
        read { transactions.find("contractId = ?1 order by pricedAt desc", contractId).list() }.map { it.toDomain() }

    override suspend fun publishedNavsUpTo(fundId: UUID, upTo: LocalDate): List<NavRecord> = read {
        navs.find(
            "fundId = ?1 and status = ?2 and valuationDate <= ?3 order by valuationDate",
            fundId,
            NavStatus.PUBLISHED.name,
            upTo,
        ).list()
    }.map { it.toDomain() }

    override suspend fun transactionsPricedAtAny(navIds: Collection<UUID>): List<UnitTransaction> =
        if (navIds.isEmpty()) {
            emptyList()
        } else {
            read { transactions.find("navId in ?1", navIds.toList()).list() }.map { it.toDomain() }
        }

    override suspend fun navPositions(navId: UUID): List<NavPosition> =
        read { positions.find("navId = ?1 order by instrumentId", navId).list() }.map { it.toDomain() }

    override suspend fun navPositionsOf(navIds: Collection<UUID>): List<NavPosition> = if (navIds.isEmpty()) {
        emptyList()
    } else {
        read { positions.find("navId in ?1 order by instrumentId", navIds.toList()).list() }.map { it.toDomain() }
    }

    override suspend fun navPosition(id: UUID): NavPosition? = read { positions.findById(id) }?.toDomain()

    override suspend fun classificationCorrection(id: UUID): PositionClassificationCorrection? =
        read { classifications.findById(id) }?.toDomain()

    override suspend fun classificationCorrections(
        positionIds: Collection<UUID>,
    ): List<PositionClassificationCorrection> = if (positionIds.isEmpty()) {
        emptyList()
    } else {
        read { classifications.find("positionId in ?1 order by proposedAt", positionIds.toList()).list() }
            .map { it.toDomain() }
    }

    // ---- mapping -----------------------------------------------------------------------------

    private fun Fund.toEntity() = FundEntity().also {
        it.id = id
        it.name = name
        it.isin = isin
        it.lei = lei
        it.depositaryReference = depositaryReference
        it.custodyAccountReference = custodyAccountReference
        it.currency = currency
        it.riskClass = riskClass.toShort()
        it.mandatoryConservative = mandatoryConservative
        it.managementFeeRate = managementFeeRate
        it.launchNavPerUnit = launchNavPerUnit
        it.status = status.name
        it.createdAt = createdAt
        it.updatedAt = updatedAt
    }

    private fun FundEntity.toDomain() = Fund(
        id = id,
        name = name,
        isin = isin,
        lei = lei,
        depositaryReference = depositaryReference,
        custodyAccountReference = custodyAccountReference,
        currency = currency,
        riskClass = riskClass.toInt(),
        mandatoryConservative = mandatoryConservative,
        managementFeeRate = managementFeeRate,
        launchNavPerUnit = launchNavPerUnit,
        status = FundStatus.valueOf(status),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun FundStrategy.toEntity() = FundStrategyEntity().also {
        it.id = id
        it.name = name
        it.allocations = json.writeValueAsString(allocations)
        it.glidePath = json.writeValueAsString(glidePath)
        it.status = status.name
        it.version = version
        it.createdAt = createdAt
        it.updatedAt = updatedAt
    }

    private fun FundStrategyEntity.toDomain() = FundStrategy(
        id = id,
        name = name,
        allocations = json.readValue<List<AllocationTarget>>(allocations),
        glidePath = json.readValue<List<GlidePathStep>>(glidePath),
        status = StrategyStatus.valueOf(status),
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun StrategyChange.toEntity() = StrategyChangeEntity().also {
        it.id = id
        it.strategyId = strategyId
        it.proposedAllocations = json.writeValueAsString(proposedAllocations)
        it.proposedGlidePath = json.writeValueAsString(proposedGlidePath)
        it.reason = reason
        it.effectiveDate = effectiveDate
        it.submittedBy = submittedBy
        it.submittedAt = submittedAt
        it.status = status.name
        it.decidedBy = decidedBy
        it.decidedAt = decidedAt
        it.participantNotificationDate = participantNotificationDate
        it.appliedAt = appliedAt
    }

    private fun StrategyChangeEntity.toDomain() = StrategyChange(
        id = id,
        strategyId = strategyId,
        proposedAllocations = json.readValue<List<AllocationTarget>>(proposedAllocations),
        proposedGlidePath = json.readValue<List<GlidePathStep>>(proposedGlidePath),
        reason = reason,
        effectiveDate = effectiveDate,
        submittedBy = submittedBy,
        submittedAt = submittedAt,
        status = StrategyChangeStatus.valueOf(status),
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        participantNotificationDate = participantNotificationDate,
        appliedAt = appliedAt,
    )

    private fun NavRecord.toEntity() = FundNavEntity().also {
        it.id = id
        it.fundId = fundId
        it.valuationDate = valuationDate
        it.grossAssets = figures.grossAssets
        it.accruedManagementFee = figures.accruedManagementFee
        it.otherLiabilities = figures.otherLiabilities
        it.netAssets = figures.netAssets
        it.unitsOutstanding = figures.unitsOutstanding
        it.navPerUnit = figures.navPerUnit
        it.status = status.name
        it.calculatedBy = calculatedBy
        it.calculatedAt = calculatedAt
        it.correctsNavId = correctsNavId
        it.approvedBy = approvedBy
        it.publishedAt = publishedAt
        it.positionsRecorded = positionsRecorded
    }

    private fun NavPosition.toEntity() = FundNavPositionEntity().also {
        // Written only inside the NAV's own (atomic) calculation commit; the id is minted with it.
        it.id = id
        it.navId = navId
        it.instrumentId = instrumentId
        it.quantity = quantity
        it.price = price
        it.instrumentClass = instrumentClass.name
    }

    private fun FundNavPositionEntity.toDomain() =
        NavPosition(id, navId, instrumentId, quantity, price, InstrumentClass.valueOf(instrumentClass))

    private fun PositionClassificationCorrection.toEntity() = PositionClassificationCorrectionEntity().also {
        it.id = id
        it.positionId = positionId
        it.navId = navId
        it.fromClass = fromClass.name
        it.toClass = toClass.name
        it.reason = reason
        it.proposedBy = proposedBy
        it.proposedAt = proposedAt
        it.status = status.name
        it.decidedBy = decidedBy
        it.decidedAt = decidedAt
    }

    private fun PositionClassificationCorrectionEntity.toDomain() = PositionClassificationCorrection(
        id = id,
        positionId = positionId,
        navId = navId,
        fromClass = InstrumentClass.valueOf(fromClass),
        toClass = InstrumentClass.valueOf(toClass),
        reason = reason,
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        status = ClassificationCorrectionStatus.valueOf(status),
        decidedBy = decidedBy,
        decidedAt = decidedAt,
    )

    private fun FundNavEntity.toDomain() = NavRecord(
        id = id,
        fundId = fundId,
        valuationDate = valuationDate,
        figures = NavFigures(
            grossAssets = grossAssets,
            accruedManagementFee = accruedManagementFee,
            otherLiabilities = otherLiabilities,
            netAssets = netAssets,
            unitsOutstanding = unitsOutstanding,
            navPerUnit = navPerUnit,
        ),
        status = NavStatus.valueOf(status),
        calculatedBy = calculatedBy,
        calculatedAt = calculatedAt,
        correctsNavId = correctsNavId,
        approvedBy = approvedBy,
        publishedAt = publishedAt,
        positionsRecorded = positionsRecorded,
    )

    private fun UnitOrder.toEntity() = UnitOrderEntity().also {
        it.id = id
        it.contractId = contractId
        it.fundId = fundId
        it.orderType = type.name
        it.amount = amount
        it.units = units
        it.targetFundId = targetFundId
        it.parentOrderId = parentOrderId
        it.status = status.name
        it.placedAt = placedAt
        it.settledAt = settledAt
        it.navId = navId
        it.idempotencyKey = idempotencyKey
    }

    private fun UnitOrderEntity.toDomain() = UnitOrder(
        id = id,
        contractId = contractId,
        fundId = fundId,
        type = OrderType.valueOf(orderType),
        amount = amount,
        units = units,
        targetFundId = targetFundId,
        parentOrderId = parentOrderId,
        status = OrderStatus.valueOf(status),
        placedAt = placedAt,
        settledAt = settledAt,
        navId = navId,
        idempotencyKey = idempotencyKey,
    )

    private fun UnitHolding.toEntity() = UnitHoldingEntity().also {
        it.id = holdingId(contractId, fundId)
        it.contractId = contractId
        it.fundId = fundId
        it.units = units
        it.version = version
    }

    private fun UnitHoldingEntity.toDomain() = UnitHolding(contractId, fundId, units, version)

    private fun UnitTransaction.toEntity() = UnitTransactionEntity().also {
        it.id = id
        it.orderId = orderId
        it.contractId = contractId
        it.fundId = fundId
        it.transactionType = type.name
        it.units = units
        it.amount = amount
        it.navId = navId
        it.navPerUnit = navPerUnit
        it.pricedAt = pricedAt
        it.correctedFromNavId = correctedFromNavId
    }

    private fun UnitTransactionEntity.toDomain() = UnitTransaction(
        id = id,
        orderId = orderId,
        contractId = contractId,
        fundId = fundId,
        type = UnitTransactionType.valueOf(transactionType),
        units = units,
        amount = amount,
        navId = navId,
        navPerUnit = navPerUnit,
        pricedAt = pricedAt,
        correctedFromNavId = correctedFromNavId,
    )

    private companion object {
        /** One row per (contract, fund): a deterministic id makes merge an upsert on that pair. */
        fun holdingId(contractId: UUID, fundId: UUID): UUID =
            UUID.nameUUIDFromBytes("holding:$contractId:$fundId".toByteArray(StandardCharsets.UTF_8))
    }
}
