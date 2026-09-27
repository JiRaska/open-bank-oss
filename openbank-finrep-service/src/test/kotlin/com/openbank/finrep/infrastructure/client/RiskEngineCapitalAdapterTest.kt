// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.client

import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** ADR-0313 D6: which risk-engine run C 02.00 reads, and what it does when there is none. */
class RiskEngineCapitalAdapterTest {

    private val asOf = LocalDate.parse("2026-09-30")

    private inner class FakeRisk(private val runs: List<SnapshotRunSummaryResponse>) : RiskEngineRestClient {
        val capitalCalls = mutableListOf<String>()
        override fun listRuns(limit: Int): Uni<SnapshotRunListResponse> =
            Uni.createFrom().item(SnapshotRunListResponse(runs))
        override fun capital(id: String): Uni<CapitalResponse> {
            capitalCalls += id
            val czk =
                CurrencyCapitalResponse(
                    "CZK",
                    listOf(ExposureClassResponse("bank", BigDecimal("100"), BigDecimal("150"))),
                    BigDecimal("150"),
                )
            return Uni.createFrom().item(
                CapitalResponse(id, asOf.toString(), "bcbs-d424-sa", "1", listOf(czk), czk, emptyList()),
            )
        }
    }

    private fun run(id: String, date: LocalDate, status: String, recordedAt: String) =
        SnapshotRunSummaryResponse(id, date.toString(), recordedAt, status)

    @Test
    fun `reads the most recently recorded TIED_OUT run at exactly the report date`() {
        val risk = FakeRisk(
            listOf(
                run("older", asOf, "TIED_OUT", "2026-10-01T08:00:00Z"),
                run("newer", asOf, "TIED_OUT", "2026-10-02T08:00:00Z"),
                run("untied", asOf, "UNTIED", "2026-10-03T08:00:00Z"),
                run("other-date", asOf.minusDays(1), "TIED_OUT", "2026-10-04T08:00:00Z"),
            ),
        )
        val lookup = runBlocking { RiskEngineCapitalAdapter(risk, enabled = true).capitalAt(asOf) }
        assertThat(risk.capitalCalls).containsExactly("newer")
        assertThat(lookup.result!!.totalRwa).isEqualByComparingTo("150")
        assertThat(lookup.result!!.classes.single().exposureClass).isEqualTo("bank")
    }

    @Test
    fun `no tied run at the date is a stated gap, never another date's figures`() {
        val risk = FakeRisk(listOf(run("untied", asOf, "UNTIED", "2026-10-03T08:00:00Z")))
        val lookup = runBlocking { RiskEngineCapitalAdapter(risk, enabled = true).capitalAt(asOf) }
        assertThat(lookup.result).isNull()
        assertThat(lookup.unavailableReason).contains("TIED_OUT")
        assertThat(risk.capitalCalls).isEmpty()
    }

    @Test
    fun `switched off, it says so and calls nothing`() {
        val risk = FakeRisk(listOf(run("r", asOf, "TIED_OUT", "2026-10-01T08:00:00Z")))
        val lookup = runBlocking { RiskEngineCapitalAdapter(risk, enabled = false).capitalAt(asOf) }
        assertThat(lookup.unavailableReason).contains("not enabled")
        assertThat(risk.capitalCalls).isEmpty()
    }
}
