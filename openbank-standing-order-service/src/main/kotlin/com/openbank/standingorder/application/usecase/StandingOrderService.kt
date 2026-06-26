// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.standingorder.application.usecase

import com.openbank.standingorder.application.port.`in`.*
import com.openbank.standingorder.application.port.out.*
import com.openbank.standingorder.domain.model.*
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class StandingOrderService(
    private val repo: StandingOrderRepository,
    private val clock: Clock,
) : StandingOrderUseCase {

    override suspend fun create(cmd: CreateStandingOrderCommand): StandingOrder {
        repo.findByIdempotencyKey(cmd.idempotencyKey)?.let { return it }
        val now = Instant.now(clock)
        val order = StandingOrder(
            id = UUID.randomUUID(), idempotencyKey = cmd.idempotencyKey,
            partyId = cmd.partyId, debitAccountId = cmd.debitAccountId,
            creditorIban = cmd.creditorIban, creditorName = cmd.creditorName, creditorBic = cmd.creditorBic,
            amountMinorUnits = cmd.amountMinorUnits, currency = cmd.currency,
            frequency = cmd.frequency, paymentType = cmd.paymentType,
            remittanceInfo = cmd.remittanceInfo,
            startDate = cmd.startDate, endDate = cmd.endDate,
            nextExecutionDate = cmd.startDate,
            lastExecutionDate = null, executionCount = 0, failureCount = 0,
            status = StandingOrderStatus.ACTIVE, createdAt = now, updatedAt = now
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
}
