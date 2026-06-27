// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.balance.domain.model.Balance
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * In-memory balance store keyed by `(account, currency)`. Wraps the real production
 * `Balance` aggregate (ADR-0100 Layer 1: time-free domain, safe for deterministic simulation).
 */
class BalanceStore {

    private val balances = mutableMapOf<AccountCurrency, Balance>()

    fun open(key: AccountCurrency, openingBooked: BigDecimal, overdraftLimit: BigDecimal) {
        balances[key] = Balance(
            id = UUID.nameUUIDFromBytes("${key.accountId}-${key.currency}".toByteArray()),
            accountId = key.accountId,
            currency = key.currency,
            bookedAmount = openingBooked,
            availableAmount = openingBooked,
            reservedAmount = BigDecimal.ZERO,
            pendingAmount = BigDecimal.ZERO,
            // Timestamps are irrelevant to simulation invariants; use epoch as stable sentinel.
            updatedAt = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
            version = 0L,
            arrangedOverdraftLimit = overdraftLimit,
        )
    }

    fun get(key: AccountCurrency): Balance = balances[key] ?: error("no balance opened for $key")

    fun has(key: AccountCurrency): Boolean = balances.containsKey(key)

    fun put(balance: Balance) {
        balances[AccountCurrency(balance.accountId, balance.currency)] = balance
    }

    fun all(): List<Balance> = balances.values.toList()
}
