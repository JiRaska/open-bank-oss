// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.rest

import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.FxUseCase
import com.openbank.fx.application.port.`in`.GetRateHistoryQuery
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FxResourceTest {

    private lateinit var fxUseCase: FxUseCase
    private lateinit var cnbIngestion: CnbRateIngestionUseCase
    private lateinit var resource: FxResource

    @BeforeEach
    fun setUp() {
        fxUseCase = mockk()
        cnbIngestion = mockk()
        resource = FxResource(fxUseCase, cnbIngestion)
    }

    @Test
    fun `getRateHistory returns 400 for unknown source`(): Unit = runBlocking {
        val resp: Response = resource.getRateHistory(
            base = "EUR",
            quote = "CZK",
            sourceStr = "RUBBISH",
            fromStr = null,
            toStr = null,
            limit = null,
            offset = null,
        )
        assertThat(resp.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.entity as Map<String, String>)["error"]).contains("Unknown source")
    }

    @Test
    fun `getRateHistory returns 400 for malformed from instant`(): Unit = runBlocking {
        val resp: Response = resource.getRateHistory(
            base = "EUR",
            quote = "CZK",
            sourceStr = null,
            fromStr = "not-a-date",
            toStr = null,
            limit = null,
            offset = null,
        )
        assertThat(resp.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.entity as Map<String, String>)["error"]).contains("Invalid 'from' instant")
    }

    @Test
    fun `getRateHistory returns 400 for malformed to instant`(): Unit = runBlocking {
        val resp: Response = resource.getRateHistory(
            base = "EUR",
            quote = "CZK",
            sourceStr = null,
            fromStr = null,
            toStr = "bad",
            limit = null,
            offset = null,
        )
        assertThat(resp.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.entity as Map<String, String>)["error"]).contains("Invalid 'to' instant")
    }

    @Test
    fun `getRateHistory returns 400 when from is after to`(): Unit = runBlocking {
        val resp: Response = resource.getRateHistory(
            base = "EUR",
            quote = "CZK",
            sourceStr = null,
            fromStr = "2026-06-14T12:00:00Z",
            toStr = "2026-01-01T00:00:00Z",
            limit = null,
            offset = null,
        )
        assertThat(resp.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.entity as Map<String, String>)["error"]).contains("'from' must be before 'to'")
    }

    @Test
    fun `getRateHistory returns 200 with empty list when use case returns nothing`(): Unit = runBlocking {
        coEvery { fxUseCase.getRateHistory(any()) } returns emptyList()

        val resp: Response = resource.getRateHistory(
            base = "EUR",
            quote = "CZK",
            sourceStr = null,
            fromStr = null,
            toStr = null,
            limit = null,
            offset = null,
        )
        assertThat(resp.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        assertThat(resp.entity as List<*>).isEmpty()
    }

    @Test
    fun `getRateHistory normalises pair to uppercase and caps limit at 365`(): Unit = runBlocking {
        val captured = slot<GetRateHistoryQuery>()
        coEvery { fxUseCase.getRateHistory(capture(captured)) } returns emptyList()

        resource.getRateHistory(
            base = "eur",
            quote = "czk",
            sourceStr = null,
            fromStr = null,
            toStr = null,
            limit = 999,
            offset = null,
        )

        assertThat(captured.isCaptured).isTrue()
        assertThat(captured.captured.baseCurrency).isEqualTo("EUR")
        assertThat(captured.captured.quoteCurrency).isEqualTo("CZK")
        assertThat(captured.captured.limit).isEqualTo(365)
    }
}
