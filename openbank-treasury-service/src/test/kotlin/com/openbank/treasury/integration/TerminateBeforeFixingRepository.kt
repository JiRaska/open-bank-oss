// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.FixingFlowRepository
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.infrastructure.marketdata.FixingFlowRepositoryImpl
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.LocalDate
import java.util.UUID

/**
 * Drives the read-then-lock race of `applyFixing` deterministically: when [armed] names a deal, the
 * hook runs (terminating that deal in its own committed transaction) AFTER `dueOn` has read the deal
 * as live and BEFORE the locked write starts. Disarmed it is a pure pass-through, so every other IT
 * in the module sees the real repository unchanged.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class TerminateBeforeFixingRepository(private val real: FixingFlowRepositoryImpl) : FixingFlowRepository {
    override suspend fun dueOn(date: LocalDate): List<CashFlow> = real.dueOn(date)

    override suspend fun fixedOn(date: LocalDate): List<CashFlow> = real.fixedOn(date)

    override suspend fun applyFixing(dealId: UUID, flows: List<CashFlow>, event: DealEvent) {
        armed?.takeIf { it.first == dealId }?.let {
            armed = null
            it.second()
        }
        real.applyFixing(dealId, flows, event)
    }

    companion object {
        @Volatile
        var armed: Pair<UUID, () -> Unit>? = null
    }
}
