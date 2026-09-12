// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * [BUFFER_MONTHS_D90] — the one feature here that mixes a balance LEVEL with a settled FLOW
 * (ADR-0282 phase 1, #8792).
 *
 * Three of these tests exist to defend a decision rather than an arithmetic result, and each names
 * the plausible "fix" it forbids:
 *
 *  - `a balance older than the window is still the balance` forbids windowing the level, which would
 *    return NaN for every dormant savings account — the healthiest buffers in the book.
 *  - `zero outflow is undefined, not an infinite buffer` forbids `POSITIVE_INFINITY`, which would
 *    pass every `>` threshold a rewards rule could write, on an entity that demonstrably has not spent.
 *  - `a negative balance yields a negative buffer` forbids clamping at zero, which would erase the
 *    customers a financial-health programme most needs to see.
 */
class BufferMonthsFeatureTest {

    private val asOf: Instant = Instant.parse("2026-09-11T12:00:00Z")

    private fun balance(minor: Long, daysAgo: Long) = FeatureEvent(
        entityId = "party-1",
        eventType = BALANCE_UPDATED,
        occurredAt = asOf.minus(Duration.ofDays(daysAgo)),
        amountMinor = minor,
    )

    /** A settled outbound payment: the instruction carries the amount, the settlement the time. */
    private fun settledOut(minor: Long, daysAgo: Long, correlation: String) = listOf(
        FeatureEvent(
            entityId = "party-1",
            eventType = TRANSACTION_INITIATED,
            occurredAt = asOf.minus(Duration.ofDays(daysAgo + 1)),
            amountMinor = minor,
            correlationId = correlation,
            direction = FlowDirection.OUT,
        ),
        FeatureEvent(
            entityId = "party-1",
            eventType = TRANSACTION_COMPLETED,
            occurredAt = asOf.minus(Duration.ofDays(daysAgo)),
            correlationId = correlation,
        ),
    )

    @Test
    fun `balance over the monthly rate of settled outflow`() {
        // 300_000 minor out over a 90-day window = 100_000 per nominal month; a 300_000 balance
        // therefore covers 3 months.
        val events = listOf(balance(300_000, daysAgo = 1)) +
            settledOut(100_000, daysAgo = 10, correlation = "a") +
            settledOut(100_000, daysAgo = 40, correlation = "b") +
            settledOut(100_000, daysAgo = 70, correlation = "c")

        assertThat(BUFFER_MONTHS_D90.compute(asOf, events)).isEqualTo(3.0)
    }

    @Test
    fun `the latest balance wins, not the largest`() {
        val events = listOf(balance(900_000, daysAgo = 30), balance(300_000, daysAgo = 1)) +
            settledOut(300_000, daysAgo = 10, correlation = "a")

        // 300_000 / (300_000 / 3) = 3.0 — the stale 900_000 would give 9.0.
        assertThat(BUFFER_MONTHS_D90.compute(asOf, events)).isEqualTo(3.0)
    }

    @Test
    fun `a balance older than the window is still the balance`() {
        // Forbids windowing the level. A dormant account: balance set 400 days ago, spending recent.
        val events = listOf(balance(300_000, daysAgo = 400)) +
            settledOut(300_000, daysAgo = 10, correlation = "a")

        assertThat(BUFFER_MONTHS_D90.compute(asOf, events))
            .`as`("windowing the balance would NaN exactly the dormant accounts with the best buffer")
            .isEqualTo(3.0)
    }

    @Test
    fun `a balance stamped exactly at asOf is not yet knowable`() {
        // The anti-leakage invariant (ADR-0140): the bound is strict, as in settledAmounts.
        val events = listOf(
            FeatureEvent("party-1", BALANCE_UPDATED, asOf, amountMinor = 300_000),
        ) + settledOut(300_000, daysAgo = 10, correlation = "a")

        assertThat(BUFFER_MONTHS_D90.compute(asOf, events)).isNaN()
    }

    @Test
    fun `no balance event at all is undefined`() {
        val events = settledOut(300_000, daysAgo = 10, correlation = "a")

        assertThat(BUFFER_MONTHS_D90.compute(asOf, events)).isNaN()
    }

    @Test
    fun `zero outflow is undefined, not an infinite buffer`() {
        val events = listOf(balance(300_000, daysAgo = 1))

        val v = BUFFER_MONTHS_D90.compute(asOf, events)
        assertThat(v).isNaN()
        // Naming the forbidden answer: infinity passes every `>` threshold a rewards rule writes.
        assertThat(v).isNotEqualTo(Double.POSITIVE_INFINITY)
    }

    @Test
    fun `a negative balance yields a negative buffer`() {
        val events = listOf(balance(-50_000, daysAgo = 1)) +
            settledOut(300_000, daysAgo = 10, correlation = "a")

        // -50_000 / 100_000 = -0.5. Clamping to 0.0 would hide an overdrawn account.
        assertThat(BUFFER_MONTHS_D90.compute(asOf, events)).isEqualTo(-0.5)
    }

    @Test
    fun `outflow outside the window does not count`() {
        val events = listOf(balance(300_000, daysAgo = 1)) +
            settledOut(300_000, daysAgo = 120, correlation = "old")

        // The only outflow is older than 90 days, so the divisor is zero -> undefined, not 3.0.
        assertThat(BUFFER_MONTHS_D90.compute(asOf, events)).isNaN()
    }

    @Test
    fun `it is registered in the money-flow catalogue`() {
        // A definition nothing registers is served by nothing; FeatureCatalogues reads this list.
        assertThat(MONEY_FLOW_FEATURES).contains(BUFFER_MONTHS_D90)
        assertThat(BUFFER_MONTHS_D90.name).isEqualTo("buffer_months_d90")
        assertThat(BUFFER_MONTHS_D90.type).isEqualTo(FeatureType.DOUBLE)
        assertThat(BUFFER_MONTHS_D90.eventTypes)
            .containsExactlyInAnyOrder(BALANCE_UPDATED, TRANSACTION_COMPLETED, TRANSACTION_INITIATED)
    }
}
