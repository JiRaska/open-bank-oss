// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.application.port.out.BookValuation
import com.openbank.treasury.application.port.out.ValuationPort
import com.openbank.treasury.application.port.out.ValuationUnavailableException
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The risk engine's valuation, scripted per test (ADR-0344 D8). Replaces [ValuationPort] in every
 * `@QuarkusTest`; the wire shape is pinned by `TreasuryRiskCurvePactConsumerTest` and replayed
 * by the risk engine instead.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class FakeValuation : ValuationPort {
    val byDate = ConcurrentHashMap<LocalDate, BookValuation>()

    @Volatile
    var down: Boolean = false

    override suspend fun latestAsOf(): LocalDate? = byDate.keys.maxOrNull()

    override suspend fun valuation(asOf: LocalDate): BookValuation? =
        if (down) throw ValuationUnavailableException("risk engine unreachable: scripted outage") else byDate[asOf]

    fun reset() {
        byDate.clear()
        down = false
    }
}
