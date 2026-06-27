// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.standingorder.application.port.`in`

import com.openbank.standingorder.domain.model.*
import java.time.LocalDate
import java.util.UUID

data class CreateStandingOrderCommand(
    val idempotencyKey: String,
    val partyId: UUID,
    val debitAccountId: UUID,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amountMinorUnits: Long,
    val currency: String,
    val frequency: Frequency,
    val paymentType: PaymentType,
    val remittanceInfo: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
)

interface StandingOrderUseCase {
    suspend fun create(cmd: CreateStandingOrderCommand): StandingOrder
    suspend fun pause(id: UUID, operatorId: String): StandingOrder
    suspend fun resume(id: UUID, operatorId: String): StandingOrder
    suspend fun cancel(id: UUID, operatorId: String): StandingOrder
    suspend fun getById(id: UUID): StandingOrder?
    suspend fun listAll(): List<StandingOrder>
    suspend fun listByParty(partyId: UUID): List<StandingOrder>
    suspend fun listByAccount(accountId: UUID): List<StandingOrder>
    suspend fun listDueForExecution(asOf: LocalDate): List<StandingOrder>
}
