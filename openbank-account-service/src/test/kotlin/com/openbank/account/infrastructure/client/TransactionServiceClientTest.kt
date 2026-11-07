// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.model.TransactionResponse
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TransactionServiceClientTest {

    private val client = mockk<TransactionServiceRestClient>()
    private val adapter = TransactionServiceClient(
        client,
        Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC),
    )
    private val accountId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun response(status: Int, transaction: TransactionResponse): Response {
        val response = mockk<Response>(relaxed = true)
        every { response.status } returns status
        every { response.hasEntity() } returns true
        every { response.readEntity(TransactionResponse::class.java) } returns transaction
        return response
    }

    @Test
    fun `a completed bonus without a transaction id remains uncertain for every accepted HTTP status`() {
        listOf(200, 201, 202, 206).forEach { status ->
            every { client.initiateTransaction(any()) } returns Uni.createFrom().item(
                response(status, TransactionResponse(status = "COMPLETED")),
            )

            assertThatThrownBy {
                runBlocking { adapter.grantWelcomeBonus(accountId, BigDecimal("50.00"), "CZK") }
            }.isInstanceOfSatisfying(TransactionCompletionUncertainException::class.java) {
                assertThat(it.status).isEqualTo(status)
                assertThat(it.cause).isNull()
            }
        }
    }

    @Test
    fun `a completed bonus with a transaction id retains accepted HTTP statuses`(): Unit = runBlocking {
        listOf(200, 201, 202, 206).forEach { status ->
            every { client.initiateTransaction(any()) } returns Uni.createFrom().item(
                response(
                    status,
                    TransactionResponse(
                        id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                        status = "COMPLETED",
                    ),
                ),
            )

            adapter.grantWelcomeBonus(accountId, BigDecimal("50.00"), "CZK")
        }
    }
}
