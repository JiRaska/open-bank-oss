// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.standingorder.application.port.`in`.CreateStandingOrderCommand
import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import com.openbank.standingorder.application.port.out.StandingOrderRepository
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.domain.model.StandingOrderStatus
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class StandingOrderService(
    private val repo: StandingOrderRepository,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : StandingOrderUseCase {

    override suspend fun create(cmd: CreateStandingOrderCommand): StandingOrder {
        repo.findByIdempotencyKey(cmd.idempotencyKey)?.let { return it }
        val now = Instant.now(clock)
        val order = StandingOrder(
            id = Ids.newId(), idempotencyKey = cmd.idempotencyKey,
            partyId = cmd.partyId, debitAccountId = cmd.debitAccountId,
            debtorIban = cmd.debtorIban, debtorName = cmd.debtorName,
            creditorIban = cmd.creditorIban, creditorName = cmd.creditorName, creditorBic = cmd.creditorBic,
            amountMinorUnits = cmd.amountMinorUnits, currency = cmd.currency,
            frequency = cmd.frequency, paymentType = cmd.paymentType,
            remittanceInfo = cmd.remittanceInfo,
            startDate = cmd.startDate, endDate = cmd.endDate,
            nextExecutionDate = cmd.startDate,
            lastExecutionDate = null, executionCount = 0, failureCount = 0,
            status = StandingOrderStatus.ACTIVE, createdAt = now, updatedAt = now,
        )
        return repo.save(order)
    }

    override suspend fun pause(id: UUID, operatorId: String) =
        repo.save((repo.findById(id) ?: error("Standing order not found: $id")).pause(Instant.now(clock)))

    override suspend fun resume(id: UUID, operatorId: String) =
        repo.save((repo.findById(id) ?: error("Standing order not found: $id")).resume(Instant.now(clock)))

    override suspend fun cancel(id: UUID, operatorId: String) =
        repo.save((repo.findById(id) ?: error("Standing order not found: $id")).cancel(Instant.now(clock)))

    override suspend fun getById(id: UUID) = repo.findById(id)
    override suspend fun listAll() = repo.listAllOrders()
    override suspend fun listByParty(partyId: UUID) = repo.findByPartyId(partyId)
    override suspend fun listByAccount(accountId: UUID) = repo.findByAccountId(accountId)
    override suspend fun listDueForExecution(asOf: LocalDate) = repo.findDueForExecution(asOf)

    override suspend fun executeOrders(asOf: LocalDate): Int {
        val due = repo.findDueForExecution(asOf)
        if (due.isEmpty()) return 0
        val now = Instant.now(clock)
        var scheduled = 0
        for (order in due) {
            try {
                val nextDate = order.calculateNextDate(order.nextExecutionDate)
                val updated = order.recordExecution(nextDate, now)
                val outboxMsg = OutboxMessage(
                    eventId = Ids.newId(),
                    aggregateId = order.id,
                    eventType = EVENT_STANDING_ORDER_DUE,
                    payload = objectMapper.writeValueAsString(
                        mapOf(
                            "orderId" to order.id,
                            // The consumer's internal-transfer route only auto-books when the
                            // resolved creditor belongs to THIS party (own-account move) — a
                            // standing order paying a different customer needs the screened
                            // domestic-payment path, not a bare transaction-service TRANSFER,
                            // which carries no AML/sanctions check of its own.
                            "partyId" to order.partyId,
                            "paymentType" to order.paymentType,
                            "debitAccountId" to order.debitAccountId,
                            "debtorIban" to order.debtorIban,
                            "debtorName" to order.debtorName,
                            "creditorIban" to order.creditorIban,
                            "creditorName" to order.creditorName,
                            "creditorBic" to order.creditorBic,
                            "amountMinorUnits" to order.amountMinorUnits,
                            "currency" to order.currency,
                            "remittanceInfo" to order.remittanceInfo,
                            "idempotencyKey" to "so-exec-${order.id}-${order.nextExecutionDate}",
                            "executionDate" to order.nextExecutionDate,
                        ),
                    ),
                )
                repo.saveWithExecution(updated, outboxMsg)
                scheduled++
            } catch (e: Exception) {
                log.errorf(e, "[standing-order-scheduler] Failed to schedule order %s — skipping", order.id)
            }
        }
        return scheduled
    }

    override suspend fun confirmExecution(id: UUID): StandingOrder {
        val order = repo.findById(id) ?: error("Standing order not found: $id")
        if (order.failureCount == 0) return order
        return repo.save(order.confirmExecution(Instant.now(clock)))
    }

    override suspend fun recordFailure(id: UUID): StandingOrder {
        val order = repo.findById(id) ?: error("Standing order not found: $id")
        val now = Instant.now(clock)
        val updated = order.recordFailure(now)
        return if (updated.status == StandingOrderStatus.FAILED) {
            val outboxMsg = OutboxMessage(
                eventId = Ids.newId(),
                aggregateId = order.id,
                eventType = EVENT_STANDING_ORDER_FAILED,
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "orderId" to order.id,
                        "partyId" to order.partyId,
                        "failureCount" to updated.failureCount,
                        "status" to updated.status,
                    ),
                ),
            )
            repo.saveWithExecution(updated, outboxMsg)
        } else {
            repo.save(updated)
        }
    }

    companion object {
        const val EVENT_STANDING_ORDER_DUE = "standing-order.due.v1"
        const val EVENT_STANDING_ORDER_FAILED = "standing-order.failed.v1"
        private val log = Logger.getLogger(StandingOrderService::class.java)
    }
}
