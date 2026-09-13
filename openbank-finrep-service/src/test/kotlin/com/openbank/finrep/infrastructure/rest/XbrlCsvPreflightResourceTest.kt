// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.infrastructure.rest

import com.openbank.finrep.application.port.inbound.GetXbrlCsvPreflightQuery
import com.openbank.finrep.application.port.inbound.XbrlCsvPreflightUseCase
import com.openbank.finrep.domain.model.XbrlCsvPreflight
import com.openbank.finrep.domain.model.XbrlCsvPreflightState
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class XbrlCsvPreflightResourceTest {

    @Test
    fun `preflight defaults reporting date to the injected clock and never returns an export`(): Unit = runBlocking {
        val useCase: XbrlCsvPreflightUseCase = mockk()
        val clock = Clock.fixed(Instant.parse("2026-06-30T10:00:00Z"), ZoneOffset.UTC)
        val query = slot<GetXbrlCsvPreflightQuery>()
        val expected = XbrlCsvPreflight(
            templateId = "F01.01",
            period = LocalDate.now(clock),
            reportingFrameworkVersion = "4.2",
            dpmVersion = "4.2.1",
            taxonomyVersion = "4.2.0.0",
            state = XbrlCsvPreflightState.BLOCKED,
            blockers = emptyList(),
        )
        coEvery { useCase.getPreflight(capture(query)) } returns expected

        val response: Response = XbrlCsvPreflightResource(useCase, clock).getPreflight("F01.01", "")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(expected)
        assertThat(query.captured.asOf).isEqualTo(LocalDate.now(clock))
    }
}
