// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.rest

import com.openbank.finrep.application.port.inbound.FinrepUseCase
import com.openbank.finrep.application.port.inbound.GetFinrepTemplateQuery
import com.openbank.finrep.application.port.inbound.TrialBalanceEvidence
import com.openbank.finrep.domain.model.BalanceVerdict
import com.openbank.finrep.domain.model.FinrepCell
import com.openbank.finrep.domain.model.FinrepTemplate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class FinrepResourceTest {

    private lateinit var finrepUseCase: FinrepUseCase
    private lateinit var fixedClock: Clock
    private lateinit var resource: FinrepResource

    private val fixedInstant: Instant = Instant.parse("2026-06-30T10:00:00Z")

    @BeforeEach
    fun setUp() {
        finrepUseCase = mockk()
        fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        resource = FinrepResource(finrepUseCase, fixedClock)
    }

    @Test
    fun `getTemplate defaults asOf to the injected clock's date when blank`(): Unit = runBlocking {
        val expected = FinrepTemplate(
            templateId = "F01.01",
            period = LocalDate.now(fixedClock),
            cells = listOf(FinrepCell(rowRef = "r010", colRef = "c010", value = BigDecimal.ZERO)),
            dataGaps = emptyList(),
            isBalanced = true,
            balanceVerdict = BalanceVerdict.AGREED_BALANCED,
        )
        val captured = slot<GetFinrepTemplateQuery>()
        coEvery { finrepUseCase.getTemplate(capture(captured)) } returns expected

        val resp: Response = resource.getTemplate(templateId = "F01.01", asOf = "")

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity).isEqualTo(expected)
        assertThat(captured.captured.asOf).isEqualTo(LocalDate.now(fixedClock))
        assertThat(captured.captured.templateId).isEqualTo("F01.01")
        assertThat(captured.captured.evidence).isEqualTo(TrialBalanceEvidence.FROZEN)
    }

    @Test
    fun `getTemplate exposes live preview only when explicitly requested`(): Unit = runBlocking {
        val expected = FinrepTemplate(
            templateId = "F01.01",
            period = LocalDate.of(2026, 7, 31),
            cells = emptyList(),
            dataGaps = emptyList(),
            isBalanced = true,
            balanceVerdict = BalanceVerdict.AGREED_BALANCED,
        )
        val captured = slot<GetFinrepTemplateQuery>()
        coEvery { finrepUseCase.getTemplate(capture(captured)) } returns expected

        resource.getTemplate("F01.01", "2026-07-31", "LIVE_PREVIEW")

        assertThat(captured.captured.evidence).isEqualTo(TrialBalanceEvidence.LIVE_PREVIEW)
    }

    @Test
    fun `getTemplate parses an explicit asOf query param`(): Unit = runBlocking {
        val explicitDate = LocalDate.of(2025, 12, 31)
        val expected = FinrepTemplate(
            templateId = "F02.00",
            period = explicitDate,
            cells = emptyList(),
            dataGaps = emptyList(),
            isBalanced = true,
            balanceVerdict = BalanceVerdict.AGREED_BALANCED,
        )
        val captured = slot<GetFinrepTemplateQuery>()
        coEvery { finrepUseCase.getTemplate(capture(captured)) } returns expected

        val resp: Response = resource.getTemplate(templateId = "F02.00", asOf = "2025-12-31")

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity).isEqualTo(expected)
        assertThat(captured.captured.asOf).isEqualTo(explicitDate)
        coVerify(exactly = 1) { finrepUseCase.getTemplate(any()) }
    }
}
