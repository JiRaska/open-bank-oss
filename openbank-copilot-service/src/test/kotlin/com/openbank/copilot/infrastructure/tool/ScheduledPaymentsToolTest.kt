// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.infrastructure.client.CustomerEdgeRestClient
import com.openbank.copilot.infrastructure.client.StandingOrderDto
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ScheduledPaymentsToolTest {

    private val client = mockk<CustomerEdgeRestClient>()
    private val tool = ScheduledPaymentsTool(client)
    private val args = ObjectMapper().createObjectNode()

    @Test
    fun `returns formatted standing orders`(): Unit = runBlocking {
        every { client.listStandingOrders() } returns Uni.createFrom().item(
            listOf(
                StandingOrderDto(
                    creditorIban = "CZ6508000000192000145399",
                    creditorName = "Alice",
                    status = "ACTIVE",
                    frequency = "MONTHLY",
                    amount = BigDecimal("2000.00"),
                    currency = "CZK",
                    nextExecutionDate = "2026-07-01",
                ),
            ),
        )

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("2000")
        assertThat(result.text).contains("Alice")
        assertThat(result.text).contains("MONTHLY")
        assertThat(result.text).contains("2026-07-01")
    }

    @Test
    fun `returns no-orders message when list is empty`(): Unit = runBlocking {
        every { client.listStandingOrders() } returns Uni.createFrom().item(emptyList())

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("žádné")
    }

    @Test
    fun `returns error on WebApplicationException`(): Unit = runBlocking {
        every { client.listStandingOrders() } returns Uni.createFrom().failure(
            jakarta.ws.rs.WebApplicationException(500),
        )

        val result = tool.call(args)

        assertThat(result.isError).isTrue()
    }
}
