// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.out.ClosedPeriodDto
import com.openbank.finrep.application.port.out.LedgerPort
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReportingPeriodServiceTest {
    private val ledger = mockk<LedgerPort>()
    private val service = ReportingPeriodService(ledger)

    @Test
    fun `only immutable frozen month evidence is available newest first`(): Unit = runBlocking {
        coEvery { ledger.listClosedPeriods() } returns listOf(
            period("2026-05-31", "MONTH", "FROZEN", "LINES_V1"),
            period("2026-06-30", "MONTH", "FROZEN", "LINES_V1"),
            period("2026-07-31", "MONTH", "DRAFT", "LINES_V1"),
            period("2026-04-30", "MONTH", "FROZEN", "HASH_ONLY"),
            period("2026-06-30", "QUARTER", "FROZEN", "LINES_V1"),
        )

        val result = service.listAvailable()

        assertThat(result.latest).isEqualTo(LocalDate.parse("2026-06-30"))
        assertThat(result.periods).containsExactly(LocalDate.parse("2026-06-30"), LocalDate.parse("2026-05-31"))
    }

    @Test
    fun `no qualifying evidence is represented honestly`(): Unit = runBlocking {
        coEvery { ledger.listClosedPeriods() } returns emptyList()

        val result = service.listAvailable()

        assertThat(result.latest).isNull()
        assertThat(result.periods).isEmpty()
    }

    private fun period(to: String, type: String, status: String, evidence: String) = ClosedPeriodDto(
        periodType = type,
        to = LocalDate.parse(to),
        status = status,
        evidenceState = evidence,
    )
}
