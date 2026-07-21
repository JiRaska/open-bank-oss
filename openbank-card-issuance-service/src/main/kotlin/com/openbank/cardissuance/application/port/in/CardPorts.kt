// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.port.`in`

import com.openbank.cardissuance.domain.model.*
import java.util.UUID

data class IssueCardCommand(
    val idempotencyKey: String,
    val partyId: UUID,
    val accountId: UUID,
    val productCode: String,
    val cardType: CardType,
    val network: CardNetwork,
    val cardholderName: String,
    val embossedName: String,
    val currency: String,
    val dailyLimitMinorUnits: Long,
    val monthlyLimitMinorUnits: Long,
    val deliveryAddress: String?,
)

data class CardStatusCommand(val cardId: UUID, val reason: String?, val changedBy: String)

data class UpdateLimitsCommand(
    val cardId: UUID,
    val dailyLimitMinorUnits: Long,
    val monthlyLimitMinorUnits: Long,
    val changedBy: String,
)

interface CardUseCase {
    suspend fun issueCard(cmd: IssueCardCommand): Card
    suspend fun activateCard(cmd: CardStatusCommand): Card
    suspend fun blockCard(cmd: CardStatusCommand): Card
    suspend fun suspendCard(cmd: CardStatusCommand): Card
    suspend fun resumeCard(cmd: CardStatusCommand): Card
    suspend fun updateLimits(cmd: UpdateLimitsCommand): Card
    suspend fun getCard(id: UUID): Card?
    suspend fun listAll(): List<Card>
    suspend fun listByAccount(accountId: UUID): List<Card>
    suspend fun listByParty(partyId: UUID): List<Card>
}
