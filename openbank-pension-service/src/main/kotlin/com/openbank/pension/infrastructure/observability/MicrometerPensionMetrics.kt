// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.observability

import com.openbank.pension.application.port.out.AnnuityQuoteOutcome
import com.openbank.pension.application.port.out.IncentiveClaimEvent
import com.openbank.pension.application.port.out.PensionMetrics
import com.openbank.pension.application.port.out.ReceiptKind
import com.openbank.pension.application.port.out.ScaConsumeOutcome
import com.openbank.pension.application.port.out.StateReturnEvent
import com.openbank.pension.application.port.out.UnmatchedResolution
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.transfer.TransferDirection
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.time.Duration

/**
 * Micrometer adapter of [PensionMetrics]. Every name below is a literal so the
 * `alert-metric-emitted` / `dashboard-metric-emitted` gates can see the emitter.
 *
 * Amounts are COUNTERS of money (`..._amount_total{currency}`), not distribution summaries: a
 * dashboard wants "how much flowed in over the window" (`increase()`), which a counter answers
 * exactly and a summary only answers through its `_sum`.
 */
@Singleton
@Suppress("TooManyFunctions") // one method per port signal
class MicrometerPensionMetrics(private val registry: MeterRegistry) : PensionMetrics {

    override fun transition(aggregate: String, from: String?, to: String) {
        Counter.builder("openbank.pension.transitions")
            .description("Pension aggregate status transitions, counted after the write returned")
            .tag("aggregate", aggregate)
            .tag("from", from ?: NONE)
            .tag("to", to)
            .register(registry)
            .increment()
    }

    override fun onboardingActivated(productLine: ProductLine, jurisdiction: String, elapsed: Duration) {
        Timer.builder("openbank.pension.onboarding.duration")
            .description("Onboarding application created -> ACTIVATED")
            .tag("product_line", productLine.name)
            .tag("jurisdiction", jurisdiction)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofMinutes(1))
            .maximumExpectedValue(MAX_ONBOARDING)
            .register(registry)
            .record(elapsed)
    }

    override fun transferFinished(
        direction: TransferDirection,
        outcome: String,
        elapsed: Duration,
        amount: BigDecimal?,
        currency: String?,
    ) {
        Timer.builder("openbank.pension.transfer.duration")
            .description("Provider transfer created -> terminal status")
            .tag("direction", direction.name)
            .tag("outcome", outcome)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofMinutes(1))
            .maximumExpectedValue(MAX_TRANSFER)
            .register(registry)
            .record(elapsed)
        if (amount != null && currency != null) {
            money("openbank.pension.transfer.amount", "Net amount of completed provider transfers")
                .tag("direction", direction.name).tag("currency", currency).register(registry).add(amount)
        }
    }

    override fun contributionReceived(
        source: ContributionSource,
        channel: ContributionChannel,
        outcome: ReceiptKind,
        amount: BigDecimal,
        currency: String,
    ) {
        Counter.builder("openbank.pension.contributions.received")
            .description("Incoming pension payments by source, channel and receipt outcome")
            .tag("source", source.name)
            .tag("channel", channel.name)
            .tag("outcome", outcome.name.lowercase())
            .register(registry)
            .increment()
        if (outcome != ReceiptKind.DUPLICATE) {
            money("openbank.pension.contributions.amount", "Money received, credited or parked as unmatched")
                .tag("source", source.name)
                .tag("outcome", outcome.name.lowercase())
                .tag("currency", currency)
                .register(registry)
                .add(amount)
        }
    }

    override fun unmatchedResolved(resolution: UnmatchedResolution) {
        Counter.builder("openbank.pension.unmatched.resolved")
            .description("Operator decisions on parked (unmatched) payments")
            .tag("resolution", resolution.name.lowercase())
            .register(registry)
            .increment()
    }

    override fun incentiveClaims(event: IncentiveClaimEvent, count: Int, amount: BigDecimal?, currency: String?) {
        Counter.builder("openbank.pension.incentive.claims")
            .description("State-incentive claims by lifecycle event (submitted = durably filed)")
            .tag("event", event.name.lowercase())
            .register(registry)
            .increment(count.toDouble())
        if (amount != null && currency != null) {
            money("openbank.pension.incentive.amount", "State-incentive money by lifecycle event")
                .tag("event", event.name.lowercase()).tag("currency", currency).register(registry)
                .add(amount)
        }
    }

    override fun stateContributionReturns(event: StateReturnEvent, count: Int, amount: BigDecimal?, currency: String?) {
        Counter.builder("openbank.pension.state_contribution.returns")
            .description("State-contribution returns (ZDPS §18) by lifecycle event")
            .tag("event", event.name.lowercase())
            .register(registry)
            .increment(count.toDouble())
        if (amount != null && currency != null) {
            money("openbank.pension.state_contribution.return_amount", "Money returned to the state agency")
                .tag("event", event.name.lowercase()).tag("currency", currency).register(registry)
                .add(amount)
        }
    }

    override fun payout(form: PayoutForm, status: String, amount: BigDecimal?, currency: String?) {
        Counter.builder("openbank.pension.payouts")
            .description("Payout requests reaching a status, by payout form")
            .tag("form", form.name)
            .tag("status", status)
            .register(registry)
            .increment()
        if (amount != null && currency != null) {
            money("openbank.pension.payout.amount", "Gross amount of payouts the participant confirmed")
                .tag("form", form.name).tag("currency", currency).register(registry).add(amount)
        }
    }

    override fun payoutAccountChange(outcome: String) {
        Counter.builder("openbank.pension.payout.account_changes")
            .description("Payout-account change requests by outcome (held = stored, not yet notified)")
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    override fun annuityQuote(partnerId: String, outcome: AnnuityQuoteOutcome) {
        Counter.builder("openbank.pension.annuity.quotes")
            .description("Annuity partner answers to quote requests")
            .tag("partner", partnerId)
            .tag("outcome", outcome.name.lowercase())
            .register(registry)
            .increment()
    }

    override fun annuitySelected(partnerId: String) {
        Counter.builder("openbank.pension.annuity.selections")
            .description("SCA-confirmed annuity offer selections by partner")
            .tag("partner", partnerId)
            .register(registry)
            .increment()
    }

    override fun strategyChange(outcome: String) {
        Counter.builder("openbank.pension.strategy.changes")
            .description("Investment strategy elections by outcome")
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    override fun scaConsume(outcome: ScaConsumeOutcome) {
        Counter.builder("openbank.pension.sca.consume")
            .description("sca-service challenge consumes by outcome")
            .tag("outcome", outcome.name.lowercase())
            .register(registry)
            .increment()
    }

    override fun idempotencyReplay(method: String) {
        Counter.builder("openbank.pension.idempotency.replays")
            .description("Requests answered from the idempotency store instead of re-executed")
            .tag("method", method)
            .register(registry)
            .increment()
    }

    override fun optimisticLockConflict(aggregate: String, outcome: String) {
        Counter.builder("openbank.pension.optimistic_lock.conflicts")
            .description("Optimistic-lock conflicts by aggregate and how they ended")
            .tag("aggregate", aggregate)
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    private fun money(name: String, description: String): Counter.Builder =
        Counter.builder(name).description(description)

    /** A money counter only grows: a zero or negative amount is skipped, never thrown on a business path. */
    private fun Counter.add(amount: BigDecimal) {
        if (amount.signum() > 0) increment(amount.toDouble())
    }

    private companion object {
        const val NONE = "NONE"
        val MAX_ONBOARDING: Duration = Duration.ofDays(60)
        val MAX_TRANSFER: Duration = Duration.ofDays(60)
    }
}
