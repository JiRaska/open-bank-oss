// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.application.port.`in`

import com.openbank.balance.domain.model.*
import java.math.BigDecimal
import java.util.UUID

data class GetBalanceQuery(val accountId: UUID, val currency: String? = null, val asOf: java.time.LocalDate? = null)
data class PlaceHoldCommand(
    val accountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val reason: String,
    val referenceId: String,
    val ttlSeconds: Long? = null,
)
data class ReleaseHoldCommand(val holdId: UUID)
data class CreditAccountCommand(
    val accountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val referenceId: String,
)
data class DebitAccountCommand(
    val accountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val referenceId: String,
)
data class InitializeBalanceCommand(
    val accountId: UUID,
    val currency: String,
    val initialAmount: BigDecimal = BigDecimal.ZERO,
    val arrangedOverdraftLimit: BigDecimal = BigDecimal.ZERO,
)
data class SetOverdraftLimitCommand(val accountId: UUID, val currency: String, val arrangedOverdraftLimit: BigDecimal)

interface BalanceUseCase {
    suspend fun getBalance(query: GetBalanceQuery): Balance
    suspend fun getBalances(accountId: UUID): List<Balance>
    suspend fun placeHold(cmd: PlaceHoldCommand): BalanceHold
    suspend fun releaseHold(cmd: ReleaseHoldCommand): BalanceHold
    suspend fun credit(cmd: CreditAccountCommand): Balance
    suspend fun debit(cmd: DebitAccountCommand): Balance
    suspend fun initializeBalance(cmd: InitializeBalanceCommand): Balance
    suspend fun setOverdraftLimit(cmd: SetOverdraftLimitCommand): Balance
}
