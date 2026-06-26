// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.infrastructure.client.CustomerEdgeRestClient
import com.openbank.copilot.infrastructure.client.StatementDto
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class StatementsToolTest {

    private val client = mockk<CustomerEdgeRestClient>()
    private val tool = StatementsTool(client)
    private val mapper = ObjectMapper()

    private val accountId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun args(id: String?) = mapper.createObjectNode().also { node ->
        if (id != null) node.put("accountId", id)
    }

    @Test
    fun `returns formatted statements`(): Unit = runBlocking {
        every { client.listStatements(accountId) } returns Uni.createFrom().item(
            listOf(
                StatementDto(
                    pocketCurrency = "CZK",
                    periodFrom = "2026-05-01",
                    periodTo = "2026-05-31",
                    legalSequenceNumber = 5,
                    openingBalance = BigDecimal("10000.00"),
                    closingBalance = BigDecimal("9500.00"),
                    entryCount = 12,
                    status = "CLOSED",
                ),
            ),
        )

        val result = tool.call(args(accountId.toString()))

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("2026-05-01")
        assertThat(result.text).contains("9500")
        assertThat(result.text).contains("12")
    }

    @Test
    fun `returns no-statements message when list is empty`(): Unit = runBlocking {
        every { client.listStatements(accountId) } returns Uni.createFrom().item(emptyList())

        val result = tool.call(args(accountId.toString()))

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("žádné")
    }

    @Test
    fun `returns error when accountId is missing`(): Unit = runBlocking {
        val result = tool.call(args(null))

        assertThat(result.isError).isTrue()
        assertThat(result.text).contains("accountId")
    }

    @Test
    fun `returns error when accountId is not a UUID`(): Unit = runBlocking {
        val result = tool.call(args("not-a-uuid"))

        assertThat(result.isError).isTrue()
    }

    @Test
    fun `returns error on WebApplicationException`(): Unit = runBlocking {
        every { client.listStatements(accountId) } returns Uni.createFrom().failure(
            jakarta.ws.rs.WebApplicationException(403),
        )

        val result = tool.call(args(accountId.toString()))

        assertThat(result.isError).isTrue()
    }
}
