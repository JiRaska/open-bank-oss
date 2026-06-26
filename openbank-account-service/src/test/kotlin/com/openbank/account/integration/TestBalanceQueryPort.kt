// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.integration

import com.openbank.account.application.port.out.BalanceQueryPort
import com.openbank.account.application.port.out.BalanceView
import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory stand-in for the balance-service REST client so account-service integration tests
 * stay self-contained (N3 / ADR-0024 moves operational money out to the balance-service).
 */
@Mock
@ApplicationScoped
class TestBalanceQueryPort : BalanceQueryPort {

    private val balances = ConcurrentHashMap<Pair<UUID, String>, BalanceView>()

    override suspend fun initialize(accountId: UUID, currency: String, arrangedOverdraftLimit: BigDecimal) {
        balances[accountId to currency] = BalanceView(
            accountId = accountId,
            currency = currency,
            booked = BigDecimal.ZERO,
            available = BigDecimal.ZERO,
            reserved = BigDecimal.ZERO,
            pending = BigDecimal.ZERO,
            arrangedOverdraftLimit = arrangedOverdraftLimit,
            updatedAt = Instant.now(),
        )
    }

    override suspend fun getByAccount(accountId: UUID): List<BalanceView> =
        balances.filterKeys { it.first == accountId }.values.toList()

    override suspend fun getByAccountAndCurrency(accountId: UUID, currency: String): BalanceView? =
        balances[accountId to currency]
}
