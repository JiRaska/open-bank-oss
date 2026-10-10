// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.observability

import com.openbank.pension.application.port.out.AnnuityQuoteOutcome
import com.openbank.pension.application.port.out.StateReturnEvent
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.transfer.TransferDirection
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.TimeUnit

/** The adapter's names, tags and VALUES — the contract the dashboards and alert rules read (#12424). */
class MicrometerPensionMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = MicrometerPensionMetrics(registry)

    @Test
    fun `a created aggregate is a transition from NONE`() {
        metrics.transition("payout_request", null, "QUOTED")
        metrics.transition("payout_request", "QUOTED", "CONFIRMED")
        metrics.transition("payout_request", "QUOTED", "CONFIRMED")

        fun t(from: String, to: String) = registry.find("openbank.pension.transitions")
            .tags("aggregate", "payout_request", "from", from, "to", to).counter()?.count()
        assertThat(t("NONE", "QUOTED")).isEqualTo(1.0)
        assertThat(t("QUOTED", "CONFIRMED")).isEqualTo(2.0)
    }

    @Test
    fun `a completed transfer records its duration and net amount, a failed one only its duration`() {
        metrics.transferFinished(TransferDirection.IN, "COMPLETED", Duration.ofDays(3), BigDecimal("120000.50"), "CZK")
        metrics.transferFinished(TransferDirection.IN, "TIMED_OUT", Duration.ofDays(30), null, null)

        val completed = registry.find("openbank.pension.transfer.duration")
            .tags("direction", "IN", "outcome", "COMPLETED").timer()!!
        assertThat(completed.count()).isEqualTo(1)
        assertThat(completed.totalTime(TimeUnit.DAYS)).isEqualTo(3.0)
        assertThat(
            registry.find("openbank.pension.transfer.amount").tags("direction", "IN", "currency", "CZK")
                .counter()!!.count(),
        ).isEqualTo(120000.5)
        assertThat(registry.find("openbank.pension.transfer.duration").tag("outcome", "TIMED_OUT").timer()!!.count())
            .isEqualTo(1)
    }

    @Test
    fun `only a CONFIRMED payout carries money, by form`() {
        metrics.payout(PayoutForm.LUMP_SUM, "QUOTED", null, null)
        metrics.payout(PayoutForm.LUMP_SUM, "CONFIRMED", BigDecimal("50000"), "CZK")

        assertThat(
            registry.find("openbank.pension.payouts").tags("form", "LUMP_SUM", "status", "CONFIRMED").counter()!!
                .count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.find("openbank.pension.payout.amount").tags("form", "LUMP_SUM", "currency", "CZK").counter()!!
                .count(),
        ).isEqualTo(50000.0)
    }

    @Test
    fun `a negative or zero amount never moves a money counter and never throws`() {
        metrics.stateContributionReturns(StateReturnEvent.SETTLED, 1, BigDecimal("-10"), "CZK")
        metrics.stateContributionReturns(StateReturnEvent.SETTLED, 1, BigDecimal("90"), "CZK")
        metrics.stateContributionReturns(StateReturnEvent.SETTLED, 1, BigDecimal.ZERO, "CZK")

        assertThat(
            registry.find("openbank.pension.state_contribution.return_amount").tag("event", "settled").counter()!!
                .count(),
        ).isEqualTo(90.0)
        assertThat(
            registry.find("openbank.pension.state_contribution.returns").tag("event", "settled").counter()!!.count(),
        ).isEqualTo(3.0)
    }

    @Test
    fun `onboarding activation duration is labelled by product line and jurisdiction only`() {
        metrics.onboardingActivated(ProductLine.DPS, "CZ", Duration.ofDays(5))
        val timer = registry.find("openbank.pension.onboarding.duration").timer()!!
        assertThat(timer.id.tags.map { it.key }).containsExactlyInAnyOrder("product_line", "jurisdiction")
        assertThat(timer.totalTime(TimeUnit.DAYS)).isEqualTo(5.0)
    }

    @Test
    fun `annuity quotes count per partner and outcome`() {
        metrics.annuityQuote("partner-a", AnnuityQuoteOutcome.OFFERED)
        metrics.annuityQuote("partner-b", AnnuityQuoteOutcome.FAILED)
        metrics.annuityQuote("partner-b", AnnuityQuoteOutcome.FAILED)
        assertThat(
            registry.find("openbank.pension.annuity.quotes").tags("partner", "partner-b", "outcome", "failed")
                .counter()!!.count(),
        ).isEqualTo(2.0)
    }
}
