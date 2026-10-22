// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.FinrepUseCase
import com.openbank.finrep.application.port.inbound.GetXbrlCsvPreflightQuery
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.F0101Mapper
import com.openbank.finrep.domain.model.BalanceVerdict
import com.openbank.finrep.domain.model.FinrepDataGap
import com.openbank.finrep.domain.model.FinrepTemplate
import com.openbank.finrep.domain.model.XbrlCsvPreflightState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class XbrlCsvPreflightServiceTest {

    private val finrepUseCase: FinrepUseCase = mockk()
    private val asOf = LocalDate.of(2026, 6, 30)

    @Test
    fun `blocks rendering for unmapped official cells even when the trial balance agrees`(): Unit = runBlocking {
        coEvery { finrepUseCase.getTemplate(any()) } returns template(
            dataGaps = listOf(FinrepDataGap("UNMAPPED_OFFICIAL_CELLS", "F01.01 except r0380/c0010", "missing")),
            isBalanced = true,
            balanceVerdict = BalanceVerdict.AGREED_BALANCED,
        )

        val result = XbrlCsvPreflightService(finrepUseCase)
            .getPreflight(GetXbrlCsvPreflightQuery("F01.01", asOf))

        assertThat(result.state).isEqualTo(XbrlCsvPreflightState.BLOCKED)
        assertThat(result.blockers).extracting<String> { it.code }.containsExactly("INCOMPLETE_OFFICIAL_MAPPING")
        assertThat(result.reportingFrameworkVersion).isEqualTo("4.2")
        assertThat(result.dpmVersion).isEqualTo("4.2.1")
        assertThat(result.taxonomyVersion).isEqualTo("4.2.0.0")
    }

    @Test
    fun `blocks rendering when the independent trial balance checks do not agree`(): Unit = runBlocking {
        coEvery { finrepUseCase.getTemplate(any()) } returns template(
            dataGaps = emptyList(),
            isBalanced = false,
            balanceVerdict = BalanceVerdict.SOURCES_DISAGREE,
        )

        val result = XbrlCsvPreflightService(finrepUseCase)
            .getPreflight(GetXbrlCsvPreflightQuery("F01.01", asOf))

        assertThat(result.state).isEqualTo(XbrlCsvPreflightState.BLOCKED)
        assertThat(result.blockers).extracting<String> { it.code }.containsExactly("TRIAL_BALANCE_NOT_AGREED")
    }

    @Test
    fun `marks a complete and independently balanced mapping ready for a later renderer`(): Unit = runBlocking {
        coEvery { finrepUseCase.getTemplate(any()) } returns template(
            dataGaps = emptyList(),
            isBalanced = true,
            balanceVerdict = BalanceVerdict.AGREED_BALANCED,
        )

        val result = XbrlCsvPreflightService(finrepUseCase)
            .getPreflight(GetXbrlCsvPreflightQuery("F01.01", asOf))

        assertThat(result.state).isEqualTo(XbrlCsvPreflightState.READY_FOR_RENDERING)
        assertThat(result.blockers).isEmpty()
    }

    @Test
    fun `blocks a return that contains a cell with an unverified datapoint id`(): Unit = runBlocking {
        val mapped = F0101Mapper.map(
            TrialBalanceSnapshot(
                lines = listOf(
                    TrialBalanceLineDto("1510", "ASSET", BigDecimal("100"), "CZK"),
                    TrialBalanceLineDto("4100", "INCOME", BigDecimal("-100"), "CZK"),
                ),
                ledgerReportsBalanced = true,
            ),
            asOf,
        )
        assertThat(mapped.hasDataGaps).isFalse()
        assertThat(mapped.isBalanced).isTrue()
        coEvery { finrepUseCase.getTemplate(any()) } returns mapped

        val result = XbrlCsvPreflightService(finrepUseCase)
            .getPreflight(GetXbrlCsvPreflightQuery("F01.01", asOf))

        assertThat(result.state).isEqualTo(XbrlCsvPreflightState.BLOCKED)
        assertThat(result.blockers).extracting<String> { it.code }.containsExactly("UNVERIFIED_DATAPOINT_ID")
        assertThat(result.blockers.single().reason).contains("F01.01/r0030/c0010")
    }

    private fun template(
        dataGaps: List<FinrepDataGap>,
        isBalanced: Boolean,
        balanceVerdict: BalanceVerdict,
    ): FinrepTemplate = FinrepTemplate(
        templateId = "F01.01",
        period = asOf,
        cells = emptyList(),
        dataGaps = dataGaps,
        isBalanced = isBalanced,
        balanceVerdict = balanceVerdict,
    )
}
