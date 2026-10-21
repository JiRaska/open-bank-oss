// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.valuation

import java.time.Duration

/** A valuation that ran past its wall-clock budget; mapped to 503 `COMPUTATION_TIMEOUT` at the edge. */
class ComputationTimeoutException(val operation: String, val budget: Duration) :
    RuntimeException("$operation exceeded its ${budget.toMillis()} ms budget and was stopped")

/**
 * A wall-clock budget checked COOPERATIVELY between units of work (a leg revaluation, a VaR
 * scenario). Pricing is CPU-bound BigDecimal arithmetic with no suspension point, so neither a
 * coroutine `withTimeout` nor a fault-tolerance `@Timeout` can stop it — both only cancel at a
 * suspension or interrupt, and the computation would keep its thread to the end. The check below
 * makes the work itself stop within one unit of the budget.
 */
class Deadline(
    private val operation: String,
    private val budget: Duration,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val end = nanoClock() + budget.toNanos()

    fun check() {
        if (nanoClock() - end > 0) throw ComputationTimeoutException(operation, budget)
    }

    companion object {
        /** No budget — for the domain's own tests and internal callers that bound work otherwise. */
        val NONE = Deadline("unbounded", Duration.ofDays(UNBOUNDED_DAYS))
        private const val UNBOUNDED_DAYS = 36_500L
    }
}
