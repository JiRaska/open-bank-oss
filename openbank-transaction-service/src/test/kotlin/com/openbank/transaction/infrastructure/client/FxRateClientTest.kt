// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Unit coverage for [FxRateClient] — in particular the 404-means-no-rate mapping. */
class FxRateClientTest {

    private val restClient: FxServiceRestClient = mockk()
    private val client = FxRateClient(restClient)

    @Test
    fun `getRate maps a successful response to an FxRateView`(): Unit = runBlocking {
        every { restClient.getRate("CZK", "EUR") } returns
            Uni.createFrom().item(FxRateResponse("CZK", "EUR", BigDecimal("0.040"), BigDecimal("0.041")))

        val result = client.getRate("CZK", "EUR")

        assertThat(result).isNotNull
        assertThat(result!!.baseCurrency).isEqualTo("CZK")
        assertThat(result.quoteCurrency).isEqualTo("EUR")
        assertThat(result.bidRate).isEqualTo(BigDecimal("0.040"))
        assertThat(result.askRate).isEqualTo(BigDecimal("0.041"))
    }

    @Test
    fun `getRate returns null when fx-service responds 404 (no rate for the pair)`(): Unit = runBlocking {
        val notFound = WebApplicationException(Response.status(404).build())
        every { restClient.getRate("CZK", "XXX") } returns Uni.createFrom().failure(notFound)

        val result = client.getRate("CZK", "XXX")

        assertThat(result).isNull()
    }

    @Test
    fun `getRate rethrows on a non-404 failure`(): Unit = runBlocking {
        val serverError = WebApplicationException(Response.status(503).build())
        every { restClient.getRate("CZK", "USD") } returns Uni.createFrom().failure(serverError)

        assertThatThrownBy {
            runBlocking { client.getRate("CZK", "USD") }
        }.isInstanceOf(WebApplicationException::class.java)
    }
}
