// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.infrastructure.client.CardDto
import com.openbank.copilot.infrastructure.client.CustomerEdgeRestClient
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CardStatusToolTest {

    private val client = mockk<CustomerEdgeRestClient>()
    private val tool = CardStatusTool(client)
    private val args = ObjectMapper().createObjectNode()

    @Test
    fun `returns formatted card list`(): Unit = runBlocking {
        every { client.listCards() } returns Uni.createFrom().item(
            listOf(
                CardDto(
                    id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    maskedPan = "4111 **** **** 1234",
                    cardType = "DEBIT",
                    network = "VISA",
                    status = "ACTIVE",
                    expiryDate = "2027-12-31",
                    currency = "CZK",
                ),
            ),
        )

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("DEBIT")
        assertThat(result.text).contains("ACTIVE")
        assertThat(result.text).contains("4111")
    }

    @Test
    fun `returns no-cards message when list is empty`(): Unit = runBlocking {
        every { client.listCards() } returns Uni.createFrom().item(emptyList())

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("žádné")
    }

    @Test
    fun `returns error on WebApplicationException`(): Unit = runBlocking {
        every { client.listCards() } returns Uni.createFrom().failure(
            jakarta.ws.rs.WebApplicationException(503),
        )

        val result = tool.call(args)

        assertThat(result.isError).isTrue()
    }
}
