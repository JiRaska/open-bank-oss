// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.standingorder.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.standingorder.domain.model.StandingOrder
import java.time.LocalDate
import java.util.UUID

/** Outbound persistence port for the standing-order aggregate. */
interface StandingOrderRepository {

    suspend fun save(order: StandingOrder): StandingOrder

    suspend fun findById(id: UUID): StandingOrder?

    suspend fun findByIdempotencyKey(key: String): StandingOrder?

    suspend fun listAllOrders(): List<StandingOrder>

    suspend fun findByPartyId(partyId: UUID): List<StandingOrder>

    suspend fun findByAccountId(accountId: UUID): List<StandingOrder>

    suspend fun findDueForExecution(asOf: LocalDate): List<StandingOrder>

    suspend fun saveWithExecution(order: StandingOrder, outboxMessage: OutboxMessage): StandingOrder
}
