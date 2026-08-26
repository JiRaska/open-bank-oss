// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.rest

import com.openbank.finrep.application.port.inbound.CorepUseCase
import com.openbank.finrep.application.port.inbound.GetCorepTemplateQuery
import com.openbank.finrep.application.port.inbound.TrialBalanceEvidence
import com.openbank.finrep.domain.model.CorepCell
import com.openbank.finrep.domain.model.CorepTemplate
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

class CorepResourceTest {

    private lateinit var corepUseCase: CorepUseCase
    private lateinit var fixedClock: Clock
    private lateinit var resource: CorepResource

    private val fixedInstant: Instant = Instant.parse("2026-06-30T10:00:00Z")

    @BeforeEach
    fun setUp() {
        corepUseCase = mockk()
        fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        resource = CorepResource(corepUseCase, fixedClock)
    }

    @Test
    fun `getTemplate defaults asOf to the injected clock's date when blank`(): Unit = runBlocking {
        val expected = CorepTemplate(
            templateId = "C_01.00",
            period = LocalDate.now(fixedClock),
            cells = listOf(
                CorepCell(
                    rowRef = "r010",
                    colRef = "c010",
                    label = "OWN FUNDS",
                    value = BigDecimal.ZERO,
                    isDataGap = true,
                    gapReason = "no capital accounts",
                ),
            ),
        )
        val captured = slot<GetCorepTemplateQuery>()
        coEvery { corepUseCase.getTemplate(capture(captured)) } returns expected

        val resp: Response = resource.getTemplate(templateId = "C_01.00", asOf = "")

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity).isEqualTo(expected)
        assertThat(captured.captured.asOf).isEqualTo(LocalDate.now(fixedClock))
        assertThat(captured.captured.templateId).isEqualTo("C_01.00")
        assertThat(captured.captured.evidence).isEqualTo(TrialBalanceEvidence.FROZEN)
    }

    @Test
    fun `getTemplate parses an explicit asOf query param`(): Unit = runBlocking {
        val explicitDate = LocalDate.of(2025, 12, 31)
        val expected = CorepTemplate(templateId = "C_01.00", period = explicitDate, cells = emptyList())
        val captured = slot<GetCorepTemplateQuery>()
        coEvery { corepUseCase.getTemplate(capture(captured)) } returns expected

        val resp: Response = resource.getTemplate(templateId = "C_01.00", asOf = "2025-12-31")

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity).isEqualTo(expected)
        assertThat(captured.captured.asOf).isEqualTo(explicitDate)
        coVerify(exactly = 1) { corepUseCase.getTemplate(any()) }
    }
}
