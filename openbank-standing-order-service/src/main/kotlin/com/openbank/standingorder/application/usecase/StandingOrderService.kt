// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.standingorder.application.port.`in`.CreateStandingOrderCommand
import com.openbank.standingorder.application.port.`in`.StandingOrderReceipt
import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import com.openbank.standingorder.application.port.out.StandingOrderRepository
import com.openbank.standingorder.domain.error.SepaCreditSchemeRules
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
        repo.findByIdempotencyKey(cmd.idempotencyKey)?.let {
            val replay = replayOrRefuse(it, cmd)
            // Preserve #11938's fail-closed check for a legacy non-EUR SEPA order. A changed
            // payload reaches the durable fingerprint first and is consistently a 409.
            SepaCreditSchemeRules.requireRailCurrency(cmd.paymentType, cmd.currency)
            return replay
        }
        // #11938: a new SEPA_CREDIT order executes as an SCT, which is euro-only. An invalid
        // create or edit is refused before any write or cancellation.
        SepaCreditSchemeRules.requireRailCurrency(cmd.paymentType, cmd.currency)
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
            requestHash = cmd.requestHash,
            initiatingPrincipal = cmd.initiatingPrincipal,
            initiatingPartyId = cmd.initiatingPartyId,
            initiatingActorId = cmd.initiatingActorId,
        )
        try {
            val replaced = cmd.replacesStandingOrderId ?: return repo.save(order)
            // An edit: create the replacement and cancel the original atomically, so a customer
            // is never debited by both (#10281). The database unique key arbitrates racing creates.
            if (!repo.replace(order, replaced, now)) {
                // A same-key replacement racing the winner reaches this branch after the
                // original has become CANCELLED. Its persisted successor still owns the key.
                repo.findByIdempotencyKey(cmd.idempotencyKey)?.let { return replayOrRefuse(it, cmd) }
                error("Standing order $replaced is not an ACTIVE or PAUSED order of this party; nothing was changed")
            }
            return order
        } catch (failure: RuntimeException) {
            // A concurrent create can pass the first read in both requests. Recover only the
            // standing_orders idempotency-key conflict; unrelated DB failures must propagate.
            if (!isIdempotencyConflict(failure)) throw failure
            val winner = repo.findByIdempotencyKey(cmd.idempotencyKey) ?: throw failure
            return replayOrRefuse(winner, cmd)
        }
    }

    override suspend fun findReceipt(
        key: String,
        debitAccountId: UUID,
        initiatingPrincipal: String,
        initiatingPartyId: UUID,
        initiatingActorId: UUID,
    ): StandingOrderReceipt {
        val order = repo.findByIdempotencyKey(key) ?: return StandingOrderReceipt("UNKNOWN")
        // A key is correlation, never authorization. Legacy and rolling-deploy rows without the
        // complete durable binding are indistinguishable from other actors' requests here.
        if (
            order.requestHash == null ||
            order.initiatingPrincipal != initiatingPrincipal ||
            order.initiatingPartyId != initiatingPartyId ||
            order.initiatingActorId != initiatingActorId ||
            order.debitAccountId != debitAccountId ||
            order.partyId != initiatingPartyId
        ) {
            return StandingOrderReceipt("UNKNOWN")
        }
        return StandingOrderReceipt("FOUND", order.id, order.status.name)
    }

    private fun replayOrRefuse(existing: StandingOrder, cmd: CreateStandingOrderCommand): StandingOrder {
        if (
            existing.requestHash != cmd.requestHash ||
            existing.initiatingPrincipal != cmd.initiatingPrincipal ||
            existing.initiatingPartyId != cmd.initiatingPartyId ||
            existing.initiatingActorId != cmd.initiatingActorId ||
            existing.partyId != cmd.partyId ||
            existing.debitAccountId != cmd.debitAccountId
        ) {
            throw IdempotencyKeyReusedException()
        }
        return existing
    }

    private fun isIdempotencyConflict(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
            .any { it.message?.contains("standing_orders_idempotency_key_key", ignoreCase = true) == true }

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
