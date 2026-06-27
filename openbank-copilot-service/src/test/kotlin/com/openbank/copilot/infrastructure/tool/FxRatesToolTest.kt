// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.infrastructure.client.CustomerEdgeRestClient
import com.openbank.copilot.infrastructure.client.FxRateDto
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FxRatesToolTest {

    private val client = mockk<CustomerEdgeRestClient>()
    private val tool = FxRatesTool(client)
    private val args = ObjectMapper().createObjectNode()

    @Test
    fun `returns formatted rates when edge responds`(): Unit = runBlocking {
        every { client.getFxRates() } returns Uni.createFrom().item(
            listOf(
                FxRateDto(base = "EUR", quote = "CZK", rate = "25.0", bid = "24.9", ask = "25.1", spreadPct = "0.40"),
                FxRateDto(base = "USD", quote = "CZK", rate = "22.5"),
            ),
        )

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("EUR/CZK")
        assertThat(result.text).contains("25.0")
        assertThat(result.text).contains("USD/CZK")
    }

    @Test
    fun `returns empty message when list is empty`(): Unit = runBlocking {
        every { client.getFxRates() } returns Uni.createFrom().item(emptyList())

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("prázdný")
    }

    @Test
    fun `returns error on WebApplicationException`(): Unit = runBlocking {
        every { client.getFxRates() } returns Uni.createFrom().failure(
            jakarta.ws.rs.WebApplicationException("upstream error"),
        )

        val result = tool.call(args)

        assertThat(result.isError).isTrue()
    }
}
