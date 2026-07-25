// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.infrastructure.client.BalanceDto
import com.openbank.copilot.infrastructure.client.CustomerEdgeRestClient
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * `BalanceTool` and `AccountsWithBalancesTool` had NO test at all before this (issue #2322)
 * — the only two of copilot's nine READ tools missing coverage. That gap is exactly what let
 * [CustomerEdgeRestClient.getBalances] declare `Uni<BalancesEnvelope>` — an object wrapper —
 * against a wire response that is a bare JSON array (customer-edge passes balance-service's own
 * `GET /api/v1/balances/{accountId}` body through unchanged). Jackson threw
 * `MismatchedInputException` on every real call; verified against the real classes before fixing
 * (`mapper.readValue<BalancesEnvelope>("[{...}]")` throws `Cannot deserialize ... from Array
 * value`). Fixed by making the client return `Uni<List<BalanceDto>>` directly.
 */
class BalanceToolTest {

    private val client = mockk<CustomerEdgeRestClient>()
    private val tool = BalanceTool(client)
    private val accountId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val args = ObjectMapper().createObjectNode().put("accountId", accountId.toString())

    @Test
    fun `returns formatted balances when the edge responds with a bare array`(): Unit = runBlocking {
        // The wire shape: customer-edge forwards balance-service's response verbatim, and
        // balance-service returns `[BalanceResponse, ...]` — a JSON array, never an object with a
        // "balances" field. This is the exact shape that broke before the fix.
        every { client.getBalances(accountId) } returns Uni.createFrom().item(
            listOf(
                BalanceDto(
                    currency = "CZK",
                    bookedAmount = BigDecimal("1000.00"),
                    availableAmount = BigDecimal("950.00"),
                ),
                BalanceDto(currency = "EUR", bookedAmount = BigDecimal("40.00"), availableAmount = BigDecimal("40.00")),
            ),
        )

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("CZK: available 950.00, booked 1000.00")
        assertThat(result.text).contains("EUR: available 40.00, booked 40.00")
    }

    @Test
    fun `returns a not-found message when the account has no balances`(): Unit = runBlocking {
        every { client.getBalances(accountId) } returns Uni.createFrom().item(emptyList())

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("No balances found")
    }

    @Test
    fun `rejects a missing accountId argument`(): Unit = runBlocking {
        val result = tool.call(ObjectMapper().createObjectNode())

        assertThat(result.isError).isTrue()
        assertThat(result.text).contains("Missing required")
    }

    @Test
    fun `rejects a malformed accountId argument`(): Unit = runBlocking {
        val result = tool.call(ObjectMapper().createObjectNode().put("accountId", "not-a-uuid"))

        assertThat(result.isError).isTrue()
        assertThat(result.text).contains("not a valid account id")
    }

    @Test
    fun `maps a 403 from the edge to an inaccessible-account message`(): Unit = runBlocking {
        every { client.getBalances(accountId) } returns Uni.createFrom().failure(
            jakarta.ws.rs.WebApplicationException(403),
        )

        val result = tool.call(args)

        assertThat(result.isError).isTrue()
        assertThat(result.text).contains("isn't accessible")
    }

    @Test
    fun `maps any other upstream failure to a service-unavailable message`(): Unit = runBlocking {
        every { client.getBalances(accountId) } returns Uni.createFrom().failure(
            jakarta.ws.rs.WebApplicationException(502),
        )

        val result = tool.call(args)

        assertThat(result.isError).isTrue()
        assertThat(result.text).contains("unavailable")
    }
}
