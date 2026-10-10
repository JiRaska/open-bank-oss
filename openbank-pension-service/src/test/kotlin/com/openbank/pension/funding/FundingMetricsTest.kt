// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.funding

import com.openbank.pension.application.usecase.ReceiptOutcome
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.IncomingPayment
import com.openbank.pension.e2e.support.StateAgencySimulator
import com.openbank.pension.infrastructure.observability.MicrometerPensionMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * The funding signals asserted by VALUE through the real use cases (ADR-0334 observability,
 * #12424): a redelivered payment must not count as a second receipt, and a payment parked as
 * unmatched must count as unmatched money — not as credited money.
 */
class FundingMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val f = InMemoryFunding(metrics = MicrometerPensionMetrics(registry))

    private fun payment(id: String, amount: String, reference: String?, date: LocalDate = LocalDate.of(2026, 1, 15)) =
        IncomingPayment(id, BigDecimal(amount), "CZK", date, reference, ContributionChannel.STANDING_ORDER)

    private fun received(outcome: String): Double = registry.find("openbank.pension.contributions.received")
        .tags("source", "PARTICIPANT", "channel", "STANDING_ORDER", "outcome", outcome)
        .counter()?.count() ?: 0.0

    private fun amount(source: String, outcome: String): Double = registry.find("openbank.pension.contributions.amount")
        .tags("source", source, "outcome", outcome, "currency", "CZK")
        .counter()?.count() ?: 0.0

    @Test
    fun `a credited payment, its redelivery and an unmatched payment are three different counts`(): Unit = runBlocking {
        val c = f.contract()
        val ref = f.contributionService.paymentReference(c.contractId)
        f.contributionService.receive(payment("pay-1", "1700", ref))
        f.contributionService.receive(payment("pay-1", "1700", ref))
        f.contributionService.receive(payment("lost", "250", "nope"))
        f.contributionService.receive(payment("lost", "250", "nope"))

        assertThat(received("credited")).isEqualTo(1.0)
        // Both redeliveries are duplicates: the credited one and the parked one.
        assertThat(received("duplicate")).isEqualTo(2.0)
        assertThat(received("unmatched")).isEqualTo(1.0)
        assertThat(amount("PARTICIPANT", "credited")).isEqualTo(1700.0)
        assertThat(amount("PARTICIPANT", "unmatched")).isEqualTo(250.0)
        // A duplicate is never money received twice.
        assertThat(amount("PARTICIPANT", "duplicate")).isZero()
    }

    @Test
    fun `an operator assignment counts the resolution and the credit it causes`(): Unit = runBlocking {
        val c = f.contract()
        val parked = (f.contributionService.receive(payment("x", "800", "nope")) as ReceiptOutcome.Unmatched).unmatched
        f.contributionService.assignUnmatched(parked.id, c.contractId, "alice")

        assertThat(
            registry.find("openbank.pension.unmatched.resolved").tag("resolution", "assigned").counter()?.count(),
        ).isEqualTo(1.0)
        assertThat(received("unmatched")).isEqualTo(1.0)
        assertThat(received("credited")).isEqualTo(1.0)
    }

    @Test
    fun `incentive claims count submission, receipt and return with their money`(): Unit = runBlocking {
        val c = f.contract()
        val ref = f.contributionService.paymentReference(c.contractId)
        f.contributionService.receive(payment("jan-1", "1000", ref, LocalDate.of(2026, 1, 5)))
        f.contributionService.receive(payment("jan-2", "1000", ref, LocalDate.of(2026, 1, 25)))

        val batch = f.incentiveService.runMonthlyClaims(YearMonth.of(2026, 1)).batches.single()
        f.incentiveService.runMonthlyClaims(YearMonth.of(2026, 1)) // nothing new: no second submission
        f.incentiveService.reconcileReceiptFile(batch.id, StateAgencySimulator.receipt(batch.payload))
        f.incentiveService.returnClaim(f.claimRows.values.single().id)

        fun claims(event: String) =
            registry.find("openbank.pension.incentive.claims").tag("event", event).counter()?.count() ?: 0.0
        fun money(event: String) = registry.find("openbank.pension.incentive.amount")
            .tags("event", event, "currency", "CZK").counter()?.count() ?: 0.0

        assertThat(claims("submitted")).isEqualTo(1.0)
        assertThat(claims("received")).isEqualTo(1.0)
        assertThat(claims("returned")).isEqualTo(1.0)
        assertThat(claims("rejected")).isZero()
        assertThat(money("submitted")).isEqualTo(340.0)
        assertThat(money("received")).isEqualTo(340.0)
        assertThat(money("returned")).isEqualTo(340.0)
        // The state money is ALSO a contribution, under its own source.
        assertThat(amount("STATE", "credited")).isEqualTo(340.0)
    }
}
