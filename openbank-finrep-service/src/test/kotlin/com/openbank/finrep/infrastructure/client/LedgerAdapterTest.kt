// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class LedgerAdapterTest {

    @Test
    fun `live preview uses the explicit mutable period endpoint and preserves the balance verdict`(): Unit =
        runBlocking {
            val client = mockk<LedgerRestClient>()
            val asOf = LocalDate.parse("2026-07-31")
            every { client.getLiveTrialBalance(asOf.toString()) } returns Uni.createFrom().item(
                ClosedPeriodTrialBalanceResponse(
                    period = "2026-07",
                    balanced = true,
                    lines = listOf(TrialBalanceLineResponse("1000", "ASSET", BigDecimal("1250.50"), "CZK")),
                ),
            )

            val snapshot = LedgerAdapter(client).getLiveTrialBalance(asOf)

            assertThat(snapshot.ledgerReportsBalanced).isTrue()
            assertThat(snapshot.lines.single().net).isEqualByComparingTo("1250.50")
            verify(exactly = 1) { client.getLiveTrialBalance("2026-07-31") }
            verify(exactly = 0) { client.getTrialBalance(any()) }
        }

    @Test
    fun `closed periods use the complete date range and preserve ledger evidence`(): Unit = runBlocking {
        val client = mockk<LedgerRestClient>()
        every { client.listClosedPeriods("1970-01-01", "9999-12-31") } returns Uni.createFrom().item(
            listOf(
                ClosedPeriodResponse(
                    periodType = "MONTH",
                    to = LocalDate.parse("2026-06-30"),
                    status = "FROZEN",
                    evidenceState = "LINES_V1",
                ),
            ),
        )

        val periods = LedgerAdapter(client).listClosedPeriods()

        assertThat(periods).hasSize(1)
        val period = periods.single()
        assertThat(period.periodType).isEqualTo("MONTH")
        assertThat(period.to).isEqualTo(LocalDate.parse("2026-06-30"))
        assertThat(period.status).isEqualTo("FROZEN")
        assertThat(period.evidenceState).isEqualTo("LINES_V1")
        verify(exactly = 1) { client.listClosedPeriods("1970-01-01", "9999-12-31") }
    }
}
