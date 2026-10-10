// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.port

import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.OrderType
import java.math.BigDecimal
import java.time.Duration

/**
 * Business and technical signals of the pension-fund service (ADR-0334 observability, #12424).
 *
 * A port so the application layer stays framework-free; the Micrometer adapter lives in
 * `infrastructure/observability`. Every method has a no-op default and [NONE] serves a hand-built
 * service in a unit test, so a missing metric never changes behaviour.
 *
 * Label discipline: closed vocabularies plus the fund's ISIN. A fund is a bounded, public,
 * regulator-registered instrument — the ISIN is neither personal data nor unbounded. Never a
 * contract, order, NAV, holding or transaction id, and never an actor.
 *
 * Names say what this service can ESTABLISH (#4348): an order is "settled" when it was priced at a
 * published NAV and the register moved, never "paid"; a NAV is "published" when its checker
 * approved it here, which says nothing about any downstream distribution.
 */
@Suppress("TooManyFunctions") // one port per signal family would scatter one observability seam
interface PensionFundMetrics {

    /** A NAV of fund [isin] reached [event]; [correction] when it re-states an already published day. */
    fun navEvent(isin: String, event: NavEvent, correction: Boolean) = Unit

    /**
     * An ORIGINAL NAV was published [lag] after its valuation day ended (00:00 UTC of the next
     * day). Corrections are not recorded here: their lag measures the error's discovery, not the
     * pipeline.
     */
    fun navPublicationLag(isin: String, lag: Duration) = Unit

    /** A published correction re-priced [transactions] transactions settled at the superseded NAV. */
    fun navCorrectionRepriced(isin: String, transactions: Int) = Unit

    /** An order of fund [isin] reached [status]: PENDING when placed, SETTLED when priced at a NAV. */
    fun order(isin: String, type: OrderType, status: OrderStatus) = Unit

    /** Money moved by settled orders, at the NAV they settled at. */
    fun settledAmount(isin: String, type: OrderType, amount: BigDecimal, currency: String) = Unit

    /** A placement retry answered with the original order (Idempotency-Key). */
    fun orderReplay(type: OrderType) = Unit

    /** A strategy change reached [event] in its four-eyes lifecycle. */
    fun strategyChange(event: StrategyChangeEvent) = Unit

    /** The answer of the market-price port to one instrument lookup. */
    fun marketPriceLookup(outcome: PriceLookupOutcome) = Unit

    /** An optimistic-lock conflict on [aggregate] refused a write; nothing was applied. */
    fun optimisticLockConflict(aggregate: String) = Unit

    companion object {
        val NONE: PensionFundMetrics = object : PensionFundMetrics {}
    }
}

enum class NavEvent { CALCULATED, PUBLISHED, REJECTED }

enum class StrategyChangeEvent { SUBMITTED, APPROVED, REJECTED, APPLIED }

/**
 * `FOUND`: the port priced the instrument. `MISSING`: it answered, with no price for that day.
 * `FAILED`: it threw — the feed is unreachable or broken.
 */
enum class PriceLookupOutcome { FOUND, MISSING, FAILED }
