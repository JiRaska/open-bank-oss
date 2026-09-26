// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.liquidity

import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.cashflow.CashFlow
import com.openbank.risk.domain.cashflow.CashFlowKind
import com.openbank.risk.domain.cashflow.SnapshotCashFlowProjection
import com.openbank.risk.domain.cashflow.SourcedFlows
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurvePillar
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.domain.model.Provenance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class LiquidityForecastTest {

    private val asOf: LocalDate = LocalDate.parse("2026-09-30")

    private fun flow(day: Long, amount: String) =
        CashFlow(asOf.plusDays(day), "CZK", CashFlowKind.PRINCIPAL, BigDecimal(amount))

    private fun hqla(value: String) = Liquidity.hqlaStock(
        listOf(
            HqlaLine(HqlaLevel.LEVEL_1, GlClass.HQLA_L1_CASH_OR_RESERVES, "1510", BigDecimal(value), BigDecimal.ZERO),
        ),
        BigDecimal("0.40"),
        BigDecimal("0.15"),
    )

    private fun forecast(
        contractual: List<CashFlow>,
        behavioural: List<CashFlow>,
        opening: String?,
        horizon: Int = 90,
    ) = LiquidityForecast.forecast(
        mapOf("CZK" to SourcedFlows(contractual, behavioural)),
        opening?.let { mapOf("CZK" to hqla(it)) } ?: emptyMap(),
        asOf,
        horizon,
    ).currencies.single()

    @Test
    fun `rows are daily to day 30 then weekly, and cover every day of the horizon exactly once`() {
        listOf(1, 7, 30, 31, 37, 38, 90, 365).forEach { h ->
            val rows = LiquidityForecast.rowBounds(h)
            val days = rows.flatMap { (a, b) -> (a..b).toList() }
            assertThat(days).`as`("horizon $h").containsExactlyElementsOf((1..h).toList())
            val daily = minOf(h, 30)
            assertThat(rows.take(daily).all { (a, b) -> a == b }).isTrue()
            assertThat(rows).hasSize(daily + (maxOf(0, h - 30) + 6) / 7)
            assertThat(rows.all { (a, b) -> b - a < 7 }).isTrue()
        }
        assertThat(LiquidityForecast.rowBounds(90).last()).isEqualTo(87 to 90)
    }

    @Test
    fun `the ladder splits flows by source and sign and cumulates from the HQLA stock`() {
        val c = forecast(
            contractual = listOf(flow(5, "100.00"), flow(45, "50.00"), flow(45, "-20.00")),
            behavioural = listOf(flow(1, "-450.00"), flow(40, "-700.00")),
            opening = "1000.00",
        )
        assertThat(c.opening).isEqualByComparingTo("1000.00")
        val byEnd = c.rows.associateBy { it.toDay }
        assertThat(byEnd.getValue(1).behaviouralOutflows).isEqualByComparingTo("-450.00")
        assertThat(byEnd.getValue(1).cumulative).isEqualByComparingTo("550.00")
        assertThat(byEnd.getValue(5).contractualInflows).isEqualByComparingTo("100.00")
        assertThat(byEnd.getValue(30).cumulative).isEqualByComparingTo("650.00")
        // weekly rows 31-37, 38-44, 45-51
        assertThat(byEnd.getValue(44).fromDay).isEqualTo(38)
        assertThat(byEnd.getValue(44).behaviouralOutflows).isEqualByComparingTo("-700.00")
        assertThat(byEnd.getValue(44).cumulative).isEqualByComparingTo("-50.00")
        val w45 = byEnd.getValue(51)
        assertThat(w45.contractualInflows).isEqualByComparingTo("50.00")
        assertThat(w45.contractualOutflows).isEqualByComparingTo("-20.00")
        assertThat(w45.net).isEqualByComparingTo("30.00")
        assertThat(w45.cumulative).isEqualByComparingTo("-20.00")
        // Every row's cumulative is the previous one plus its net.
        c.rows.zipWithNext().forEach { (a, b) ->
            assertThat(b.cumulative).isEqualByComparingTo(a.cumulative.add(b.net))
        }
        assertThat(c.rows.last().cumulative).isEqualByComparingTo("-20.00")
        assertThat(c.survivalDay).isEqualTo(40)
        assertThat(c.survivalDate).isEqualTo(asOf.plusDays(40))
        assertThat(c.minimumCumulative).isEqualByComparingTo("-50.00")
    }

    @Test
    fun `the survival day is the exact day inside a weekly row, and the first breach even if it recovers`() {
        val c = forecast(emptyList(), listOf(flow(33, "-10.00"), flow(34, "20.00")), opening = "5.00")
        assertThat(c.survivalDay).isEqualTo(33)
        assertThat(c.rows.single { it.toDay == 37 }.cumulative).isEqualByComparingTo("15.00")
    }

    @Test
    fun `no breach within the horizon is a null survival day, and zero is not a breach`() {
        val c = forecast(emptyList(), listOf(flow(1, "-100.00")), opening = "100.00")
        assertThat(c.survivalDay).isNull()
        assertThat(c.survivalDate).isNull()
        assertThat(c.minimumCumulative).isEqualByComparingTo("0")
    }

    @Test
    fun `without HQLA the opening is zero and any first-day outflow is a day-1 breach`() {
        val c = forecast(emptyList(), listOf(flow(1, "-0.01")), opening = null)
        assertThat(c.hqla).isNull()
        assertThat(c.opening).isEqualByComparingTo("0")
        assertThat(c.survivalDay).isEqualTo(1)
    }

    @Test
    fun `past-due flows count on day 1 and flows after the horizon are counted, not laddered`() {
        val c =
            forecast(listOf(flow(-3, "40.00"), flow(0, "10.00"), flow(11, "999.00")), emptyList(), "0", horizon = 10)
        assertThat(c.rows).hasSize(10)
        assertThat(c.rows.first().contractualInflows).isEqualByComparingTo("50.00")
        assertThat(c.rows.last().cumulative).isEqualByComparingTo("50.00")
        assertThat(c.flowsBeyondHorizon).isEqualTo(1)
    }

    @Test
    fun `the horizon is bounded to 1 to 365 days`() {
        assertThat(forecast(emptyList(), emptyList(), "1", horizon = 1).rows).hasSize(1)
        assertThat(forecast(emptyList(), emptyList(), "1", horizon = 365).rows.last().toDay).isEqualTo(365)
        listOf(0, 366, -1).forEach { h ->
            assertThatThrownBy { forecast(emptyList(), emptyList(), "1", horizon = h) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("horizonDays")
        }
    }

    @Test
    fun `a currency with HQLA but no flows is still forecast`() {
        val r = LiquidityForecast.forecast(emptyMap(), mapOf("EUR" to hqla("7.00")), asOf, 3)
        assertThat(r.currencies.single().currency).isEqualTo("EUR")
        assertThat(r.currencies.single().rows.map { it.cumulative }).allMatch { it.compareTo(BigDecimal("7.00")) == 0 }
        assertThat(r.assumptions.map { it.key }).contains("new-business-not-modelled", "opening-liquidity")
    }

    @Test
    fun `the forecast ladders exactly the flows the cash-flow projection derives`() {
        val positions = listOf(
            Position(PositionKind.SUB_LEDGER, "2100", "LIABILITY", "CZK", UUID.randomUUID(), BigDecimal("-1500.00")),
        )
        val czeonia = Curve(CurveIndex.CZEONIA, asOf, listOf(CurvePillar(asOf.plusYears(1), BigDecimal("0.04"))))
        val curves = CurveSet(
            UUID.randomUUID(),
            asOf,
            Provenance.SYNTHETIC,
            "test",
            Instant.EPOCH,
            mapOf(
                czeonia.index to czeonia,
            ),
        )
        val model = BehaviouralModel.NMD_PHASE0
        val flows = SnapshotCashFlowProjection.flows(positions, asOf, curves, model)
        val projected = SnapshotCashFlowProjection.project(positions, asOf, curves, model).currencies.single().total

        val c = LiquidityForecast.forecast(flows, emptyMap(), asOf, 365).currencies.single()
        val laddered = c.rows.fold(BigDecimal.ZERO) { a, r -> a.add(r.net) }
        val beyond = flows.getValue("CZK").all.filter { it.date.isAfter(asOf.plusDays(365)) }
            .fold(BigDecimal.ZERO) { a, f -> a.add(f.amount) }
        assertThat(laddered.add(beyond)).isEqualByComparingTo(projected)
        // 30% volatile leaves on the first business day, then 1050 over 60 months = 17.50 a month.
        assertThat(c.rows.first().behaviouralOutflows).isEqualByComparingTo("-450.00")
        assertThat(laddered).isEqualByComparingTo(BigDecimal("-450.00").add(BigDecimal("-210.00")))
        assertThat(c.survivalDay).isEqualTo(1)
    }
}
