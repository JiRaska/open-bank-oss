// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.model.TransactionResponse
import com.openbank.sepa.application.port.out.ReversalUnavailableException
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ReversalAdapterTest {
    private val client = mockk<TransactionServiceClient>()
    private val adapter = ReversalAdapter(client).also { it.self = it }
    private val original = UUID.randomUUID()
    private val reversalId = UUID.randomUUID()

    private fun answer(status: Int, txStatus: String? = "COMPLETED") {
        val builder = Response.status(status)
        if (txStatus != null) builder.entity(TransactionResponse(id = reversalId, status = txStatus, type = "REVERSAL"))
        every { client.reverseTransaction(original, any()) } returns Uni.createFrom().item(builder.build())
    }

    private fun reverseFails(): Throwable =
        runCatching { runBlocking { adapter.reverseTransaction(original, "sepa-reversal-1", "AC04") } }
            .exceptionOrNull() ?: error("reverseTransaction() unexpectedly succeeded")

    @Test
    fun `a COMPLETED 200 (provider replay) or 201 is a reversal with its id`(): Unit = runBlocking {
        listOf(200, 201).forEach { code ->
            answer(code)
            val outcome = adapter.reverseTransaction(original, "sepa-reversal-1", "AC04")
            assertThat(outcome.reversed).describedAs("HTTP %d", code).isTrue()
            assertThat(outcome.reversalTransactionId).isEqualTo(reversalId)
        }
    }

    @Test
    fun `HTTP 409 is a concurrent-state conflict - never reported as reversed`() {
        answer(409, txStatus = null)
        assertThat(reverseFails()).isInstanceOf(ReversalUnavailableException::class.java).hasMessageContaining("409")
    }

    @Test
    fun `HTTP 422 refusal is not a reversal`() {
        answer(422, txStatus = null)
        assertThat(reverseFails()).isInstanceOf(ReversalUnavailableException::class.java).hasMessageContaining("422")
    }

    @Test
    fun `a PENDING 200 or a 202 is not a proven reversal`() {
        answer(200, txStatus = "PENDING")
        assertThat(reverseFails()).hasCauseInstanceOf(TransactionCompletionUncertainException::class.java)
        answer(202)
        assertThat(reverseFails()).isInstanceOf(ReversalUnavailableException::class.java)
            .hasMessageContaining("Unexpected HTTP 202")
    }
}
