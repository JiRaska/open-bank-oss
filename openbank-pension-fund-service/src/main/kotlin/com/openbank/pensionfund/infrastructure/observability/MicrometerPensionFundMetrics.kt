// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.observability

import com.openbank.pensionfund.application.port.NavEvent
import com.openbank.pensionfund.application.port.PensionFundMetrics
import com.openbank.pensionfund.application.port.PriceLookupOutcome
import com.openbank.pensionfund.application.port.StrategyChangeEvent
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.OrderType
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.time.Duration

/**
 * Micrometer adapter of [PensionFundMetrics]. Every name below is a literal so the
 * `alert-metric-emitted` / `dashboard-metric-emitted` gates can see the emitter.
 *
 * Amounts are COUNTERS of money (`..._amount_total{currency}`): a dashboard wants "how much
 * settled over the window" (`increase()`), which a counter answers exactly.
 */
@Singleton
@Suppress("TooManyFunctions") // one method per port signal
class MicrometerPensionFundMetrics(private val registry: MeterRegistry) : PensionFundMetrics {

    override fun navEvent(isin: String, event: NavEvent, correction: Boolean) {
        Counter.builder("openbank.pension_fund.nav.events")
            .description("NAV pipeline events per fund: calculated (maker), published / rejected (checker)")
            .tag("fund", isin)
            .tag("event", event.name.lowercase())
            .tag("correction", correction.toString())
            .register(registry)
            .increment()
    }

    override fun navPublicationLag(isin: String, lag: Duration) {
        Timer.builder("openbank.pension_fund.nav.publication.lag")
            .description("End of the valuation day (00:00 UTC next day) -> original NAV published")
            .tag("fund", isin)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofMinutes(1))
            .maximumExpectedValue(MAX_LAG)
            .register(registry)
            .record(if (lag.isNegative) Duration.ZERO else lag)
    }

    override fun navCorrectionRepriced(isin: String, transactions: Int) {
        Counter.builder("openbank.pension_fund.nav.correction.repriced")
            .description("Unit transactions re-priced by a published NAV correction")
            .tag("fund", isin)
            .register(registry)
            .increment(transactions.toDouble())
    }

    override fun order(isin: String, type: OrderType, status: OrderStatus) {
        Counter.builder("openbank.pension_fund.orders")
            .description("Unit orders reaching a status: PENDING when placed, SETTLED when priced at a NAV")
            .tag("fund", isin)
            .tag("type", type.name)
            .tag("status", status.name)
            .register(registry)
            .increment()
    }

    override fun settledAmount(isin: String, type: OrderType, amount: BigDecimal, currency: String) {
        if (amount.signum() <= 0) return // a money counter only grows; never throw on a business path
        Counter.builder("openbank.pension_fund.settled.amount")
            .description("Money moved by settled unit orders, at the NAV they settled at")
            .tag("fund", isin)
            .tag("type", type.name)
            .tag("currency", currency)
            .register(registry)
            .increment(amount.toDouble())
    }

    override fun orderReplay(type: OrderType) {
        Counter.builder("openbank.pension_fund.order.replays")
            .description("Order placements answered with the original order (Idempotency-Key retry)")
            .tag("type", type.name)
            .register(registry)
            .increment()
    }

    override fun strategyChange(event: StrategyChangeEvent) {
        Counter.builder("openbank.pension_fund.strategy_changes")
            .description("Strategy changes by four-eyes lifecycle event")
            .tag("event", event.name.lowercase())
            .register(registry)
            .increment()
    }

    override fun marketPriceLookup(outcome: PriceLookupOutcome) {
        Counter.builder("openbank.pension_fund.market_price.lookups")
            .description("Market-price port lookups by outcome (found, missing = no price, failed = threw)")
            .tag("outcome", outcome.name.lowercase())
            .register(registry)
            .increment()
    }

    override fun optimisticLockConflict(aggregate: String) {
        Counter.builder("openbank.pension_fund.optimistic_lock.conflicts")
            .description("Writes refused by an optimistic-lock conflict (nothing applied, caller told to retry)")
            .tag("aggregate", aggregate)
            .register(registry)
            .increment()
    }

    private companion object {
        val MAX_LAG: Duration = Duration.ofDays(7)
    }
}
