// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.port.`in`

import com.openbank.balance.domain.model.*
import com.openbank.libs.domain.error.requireValid
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.util.UUID

data class GetBalanceQuery(val accountId: UUID, val currency: String? = null, val asOf: java.time.LocalDate? = null)

/**
 * Money-moving commands carry a kernel [Money] (#11604): the inbound adapter builds it with
 * `Money.parseInbound` BEFORE any idempotency lookup, persistence or event, so an amount that would
 * need rounding to fit its currency, or a currency that is not ISO 4217 with a minor unit, never
 * reaches this layer. [currency] is derived from the [Money], never carried separately, so the two
 * cannot disagree. A hold, credit or debit moves a strictly positive amount — a negative credit
 * would lower booked funds with no overdraft guard, and a negative debit would raise them.
 */
data class PlaceHoldCommand(
    val accountId: UUID,
    val amount: Money,
    val reason: String,
    val referenceId: String,
    val ttlSeconds: Long? = null,
) {
    init {
        requirePositive(amount)
    }

    val currency: String get() = amount.currency.code
}
data class ReleaseHoldCommand(val holdId: UUID)
data class CreditAccountCommand(val accountId: UUID, val amount: Money, val referenceId: String) {
    init {
        requirePositive(amount)
    }

    val currency: String get() = amount.currency.code
}
data class DebitAccountCommand(val accountId: UUID, val amount: Money, val referenceId: String) {
    init {
        requirePositive(amount)
    }

    val currency: String get() = amount.currency.code
}
data class InitializeBalanceCommand(
    val accountId: UUID,
    val initialAmount: Money,
    val arrangedOverdraftLimit: Money = Money(BigDecimal.ZERO, initialAmount.currency),
) {
    init {
        require(arrangedOverdraftLimit.currency == initialAmount.currency) {
            "arrangedOverdraftLimit currency must match the balance currency"
        }
        requireValid(arrangedOverdraftLimit.isNonNegative(), "arrangedOverdraftLimit") {
            "arrangedOverdraftLimit must not be negative"
        }
    }

    val currency: String get() = initialAmount.currency.code

    companion object {
        /** A zero balance in [currency] — the event-driven onboarding path (ADR-0267 §3). */
        fun zero(accountId: UUID, currency: CurrencyCode) =
            InitializeBalanceCommand(accountId, Money(BigDecimal.ZERO, currency))
    }
}
data class SetOverdraftLimitCommand(val accountId: UUID, val arrangedOverdraftLimit: Money) {
    init {
        requireValid(arrangedOverdraftLimit.isNonNegative(), "arrangedOverdraftLimit") {
            "arrangedOverdraftLimit must not be negative"
        }
    }

    val currency: String get() = arrangedOverdraftLimit.currency.code
}

private fun requirePositive(amount: Money) =
    requireValid(amount.isPositive(), "amount") { "amount must be greater than zero" }

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
