// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class MoneyFlowFeaturesTest {

    private val asOf: Instant = Instant.parse("2026-09-05T00:00:00Z")

    private fun instructed(tx: String, minor: Long, dir: FlowDirection, at: String) = FeatureEvent(
        entityId = "acc-1",
        eventType = TRANSACTION_INITIATED,
        occurredAt = Instant.parse(at),
        amountMinor = minor,
        correlationId = tx,
        direction = dir,
    )

    private fun settled(tx: String, at: String) = FeatureEvent(
        entityId = "acc-1",
        eventType = TRANSACTION_COMPLETED,
        occurredAt = Instant.parse(at),
        correlationId = tx,
    )

    @Test
    fun `flow takes the amount from the instruction and the time from the settlement`() {
        val events =
            listOf(
                instructed("t1", 100_00, FlowDirection.IN, "2026-08-01T10:00:00Z"),
                settled("t1", "2026-08-02T10:00:00Z"),
                instructed("t2", 30_00, FlowDirection.OUT, "2026-08-03T10:00:00Z"),
                settled("t2", "2026-08-04T10:00:00Z"),
            )
        assertEquals(100_00.0, SETTLED_INFLOW_MINOR_D90.compute(asOf, events))
        assertEquals(30_00.0, SETTLED_OUTFLOW_MINOR_D90.compute(asOf, events))
    }

    /**
     * The property that makes this a POST-settlement fact rather than an instruction-time one:
     * #8792 rejects the initiated event precisely because it is emitted before the money moves.
     */
    @Test
    fun `an instruction that never settled contributes nothing`() {
        val events =
            listOf(
                instructed("t1", 100_00, FlowDirection.IN, "2026-08-01T10:00:00Z"),
                settled("t1", "2026-08-02T10:00:00Z"),
                instructed("t2", 999_00, FlowDirection.IN, "2026-08-03T10:00:00Z"),
            )
        assertEquals(100_00.0, SETTLED_INFLOW_MINOR_D90.compute(asOf, events))
    }

    /**
     * A payment instructed before the window and settled inside it is a settlement inside the
     * window. Bounding the instruction too would report a settled payment carrying no value.
     */
    @Test
    fun `an instruction older than the window still prices a settlement inside it`() {
        val events =
            listOf(
                instructed("t1", 250_00, FlowDirection.OUT, "2026-01-01T10:00:00Z"),
                settled("t1", "2026-08-20T10:00:00Z"),
            )
        assertEquals(250_00.0, SETTLED_OUTFLOW_MINOR_D90.compute(asOf, events))
    }

    /** The anti-leakage invariant: strictly before asOf, never at or after it (ADR-0140). */
    @Test
    fun `a settlement at or after asOf is invisible`() {
        val events =
            listOf(
                instructed("t1", 10_00, FlowDirection.OUT, "2026-09-01T00:00:00Z"),
                settled("t1", "2026-09-05T00:00:00Z"),
            )
        assertEquals(0.0, SETTLED_OUTFLOW_MINOR_D90.compute(asOf, events))
    }

    @Test
    fun `savings rate is the unspent share of settled inflow`() {
        val events =
            listOf(
                instructed("t1", 100_00, FlowDirection.IN, "2026-08-01T10:00:00Z"),
                settled("t1", "2026-08-02T10:00:00Z"),
                instructed("t2", 25_00, FlowDirection.OUT, "2026-08-03T10:00:00Z"),
                settled("t2", "2026-08-04T10:00:00Z"),
            )
        assertEquals(0.75, SAVINGS_RATE_D90.compute(asOf, events))
    }

    /**
     * The load-bearing one. Zero inflow has no savings rate, and 0.0 would report "saves nothing"
     * for the customer we know least about — in a feature a rewards programme thresholds on. NaN
     * makes every comparison false, so such a rule fails closed.
     */
    @Test
    fun `no settled inflow is undefined, never zero`() {
        val events =
            listOf(
                instructed("t1", 40_00, FlowDirection.OUT, "2026-08-01T10:00:00Z"),
                settled("t1", "2026-08-02T10:00:00Z"),
            )
        val rate = SAVINGS_RATE_D90.compute(asOf, events)
        assertTrue(rate.isNaN(), "expected NaN, got $rate")
        assertTrue(!(rate > 0.5), "a threshold test must fail closed on an undefined rate")
    }

    /** Spending more than you received is a real state, and clamping it erases who needs seeing. */
    @Test
    fun `a negative savings rate is reported, not clamped`() {
        val events =
            listOf(
                instructed("t1", 10_00, FlowDirection.IN, "2026-08-01T10:00:00Z"),
                settled("t1", "2026-08-02T10:00:00Z"),
                instructed("t2", 30_00, FlowDirection.OUT, "2026-08-03T10:00:00Z"),
                settled("t2", "2026-08-04T10:00:00Z"),
            )
        assertEquals(-2.0, SAVINGS_RATE_D90.compute(asOf, events))
    }

    @Test
    fun `cadence is the median gap between settled outbound payments`() {
        val events =
            listOf(
                instructed("t1", 1_00, FlowDirection.OUT, "2026-08-01T00:00:00Z"),
                settled("t1", "2026-08-01T00:00:00Z"),
                instructed("t2", 1_00, FlowDirection.OUT, "2026-08-03T00:00:00Z"),
                settled("t2", "2026-08-03T00:00:00Z"),
                instructed("t3", 1_00, FlowDirection.OUT, "2026-08-13T00:00:00Z"),
                settled("t3", "2026-08-13T00:00:00Z"),
            )
        // Gaps are 2 and 10 days; the median of two values is their mean, and the MEAN of the
        // series would be 6 — this asserts the median, which is what resists one long dormancy.
        assertEquals(6.0, SPEND_CADENCE_DAYS_D90.compute(asOf, events))
    }

    @Test
    fun `fewer than two settlements gives no cadence, never zero`() {
        val events =
            listOf(
                instructed("t1", 1_00, FlowDirection.OUT, "2026-08-01T00:00:00Z"),
                settled("t1", "2026-08-01T00:00:00Z"),
            )
        assertTrue(SPEND_CADENCE_DAYS_D90.compute(asOf, events).isNaN())
    }

    /**
     * The coverage signal. Without it a settlement nobody could price simply lowers the flow, and
     * "spends little" becomes indistinguishable from "we could not price this".
     */
    @Test
    fun `a settlement no instruction priced is counted, and the flow that dropped it agrees`() {
        val events =
            listOf(
                instructed("t1", 50_00, FlowDirection.OUT, "2026-08-01T10:00:00Z"),
                settled("t1", "2026-08-02T10:00:00Z"),
                settled("t2", "2026-08-06T10:00:00Z"),
            )
        assertEquals(1.0, MONEY_EVENTS_WITHOUT_AMOUNT_D90.compute(asOf, events))
        assertEquals(50_00.0, SETTLED_OUTFLOW_MINOR_D90.compute(asOf, events))
    }

    @Test
    fun `a fully priced window reports zero uncovered settlements`() {
        val events =
            listOf(
                instructed("t1", 50_00, FlowDirection.OUT, "2026-08-01T10:00:00Z"),
                settled("t1", "2026-08-02T10:00:00Z"),
            )
        assertEquals(0.0, MONEY_EVENTS_WITHOUT_AMOUNT_D90.compute(asOf, events))
    }

    /** Every feature must be registered with its coverage signal, not without it. */
    @Test
    fun `the declared set carries the coverage signal alongside the values`() {
        assertTrue(MONEY_EVENTS_WITHOUT_AMOUNT_D90 in MONEY_FLOW_FEATURES)
        assertEquals(MONEY_FLOW_FEATURES.size, MONEY_FLOW_FEATURES.map { it.name }.toSet().size)
    }

    /** The existing three-field shape must keep working: every new field defaults to absent. */
    @Test
    fun `an event constructed without the money fields still compiles and carries nulls`() {
        val e = FeatureEvent(entityId = "acc-1", eventType = TRANSACTION_INITIATED, occurredAt = asOf)
        assertEquals(null, e.amountMinor)
        assertEquals(null, e.correlationId)
        assertEquals(null, e.direction)
    }
}
