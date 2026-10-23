// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.transaction

import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.clients.transaction.model.ReverseTransactionRequest
import com.openbank.clients.transaction.model.TransactionPage
import com.openbank.clients.transaction.model.TransactionResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.UUID

class TransactionCompletionTest {
    private val api = mockk<TransactionsApi>()
    private val client = TransactionClient(api)
    private val request = mockk<InitiateTransactionRequest>()
    private val reversal = mockk<ReverseTransactionRequest>()
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun response(status: String?, code: Int = 201): Response = mockk<Response>().also {
        every { it.status } returns code
        every { it.statusInfo } returns Response.Status.fromStatusCode(code)
        every { it.hasEntity() } returns true
        every { it.readEntity(TransactionResponse::class.java) } returns TransactionResponse(id = id, status = status)
        every { it.close() } returns Unit
    }

    private fun assertUncertain(call: () -> Unit) {
        assertThatThrownBy(call).isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("completion is unproven")
    }

    @TestFactory
    fun `initiate leaves every non-completed state uncertain`(): List<DynamicTest> =
        listOf("FAILED", "PENDING", "PROCESSING", "REVERSED", null, "UNRECOGNIZED").map { state ->
            DynamicTest.dynamicTest("initiate state=$state is uncertain") {
                val response = response(state)
                every { api.initiateTransaction(request) } returns Uni.createFrom().item(response)
                assertUncertain { client.initiate(request).await().indefinitely() }
                verify(exactly = 1) { response.close() }
            }
        }

    @Test
    fun `an unreadable or empty successful response cannot prove a write completed`() {
        listOf(201, 204).forEach { code ->
            val response = response("COMPLETED", code)
            every { response.hasEntity() } returns (code == 201)
            if (code == 201) {
                every { response.readEntity(TransactionResponse::class.java) } throws
                    IllegalStateException("fixture reader failure")
            }
            every { api.initiateTransaction(request) } returns Uni.createFrom().item(response)
            assertUncertain { client.initiate(request).await().indefinitely() }
            verify(exactly = 1) { response.close() }
        }
    }

    @Test
    fun `a successful status carried by an exception is still uncertain without a readable body`() {
        val response = response("COMPLETED")
        every { api.initiateTransaction(request) } returns Uni.createFrom().failure(WebApplicationException(response))
        assertUncertain { client.initiate(request).await().indefinitely() }
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `reverse validates the new credit completion and not the reversed original`() {
        listOf("FAILED", "PENDING", "REVERSED").forEach { state ->
            val response = response(state, 200)
            every { api.reverseTransaction(id, reversal) } returns Uni.createFrom().item(response)
            assertUncertain { client.reverse(id, reversal).await().indefinitely() }
            verify(exactly = 1) { response.close() }
        }
    }

    @Test
    fun `completed initiate and reversal are successful and a close failure does not change that`() {
        val response = response("COMPLETED")
        every { response.close() } throws IllegalStateException("fixture close failure")
        every { api.initiateTransaction(request) } returns Uni.createFrom().item(response)
        every { api.reverseTransaction(id, reversal) } returns Uni.createFrom().item(response)
        assertThat(
            client.initiate(request).await().indefinitely(),
        ).isInstanceOf(TransactionCallOutcome.HttpSuccess::class.java)
        assertThat(
            client.reverse(id, reversal).await().indefinitely(),
        ).isInstanceOf(TransactionCallOutcome.HttpSuccess::class.java)
        verify(exactly = 2) { response.close() }
    }

    @Test
    fun `unproven writes retain the body error and HTTP status without echoing its payload`() {
        val response = response("COMPLETED")
        val failure = IllegalStateException("fixture private body error")
        every { response.readEntity(TransactionResponse::class.java) } throws failure
        every { api.initiateTransaction(request) } returns Uni.createFrom().item(response)
        assertThatThrownBy { client.initiate(request).await().indefinitely() }
            .isInstanceOfSatisfying(TransactionCompletionUncertainException::class.java) {
                assertThat(it.status).isEqualTo(201)
                assertThat(it.cause).isSameAs(failure)
                assertThat(it.message).doesNotContain("private body")
            }
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `write rejections and conflicts keep the transport classification`() {
        listOf(400, 409, 422).forEach { code ->
            val response = response(null, code)
            every { response.hasEntity() } returns false
            every { api.initiateTransaction(request) } returns Uni.createFrom().item(response)
            val result = client.initiate(request).await().indefinitely()
            if (code == 409) {
                assertThat(result).isEqualTo(TransactionCallOutcome.Conflict)
            } else {
                assertThat(result).isInstanceOf(TransactionCallOutcome.Rejected::class.java)
            }
            verify(exactly = 1) { response.close() }
        }
    }

    @Test
    fun `typed uncertainty distinguishes FAILED from noncompleted and unreadable responses`() {
        listOf("FAILED", "PENDING", null).forEach { state ->
            val response = response(state)
            every { api.initiateTransaction(request) } returns Uni.createFrom().item(response)
            assertThatThrownBy { client.initiate(request).await().indefinitely() }
                .isInstanceOfSatisfying(TransactionCompletionUncertainException::class.java) {
                    val expected = if (state == "FAILED") {
                        TransactionCompletionFailure.FAILED
                    } else {
                        TransactionCompletionFailure.UNPROVEN
                    }
                    assertThat(it.completionFailure).isEqualTo(expected)
                    assertThat(it.status).isEqualTo(201)
                }
        }
        val response = response("COMPLETED")
        every { response.hasEntity() } returns false
        every { api.reverseTransaction(id, reversal) } returns Uni.createFrom().item(response)
        assertThatThrownBy { client.reverse(id, reversal).await().indefinitely() }
            .isInstanceOfSatisfying(TransactionCompletionUncertainException::class.java) {
                assertThat(it.completionFailure).isEqualTo(TransactionCompletionFailure.UNPROVEN)
            }
    }

    @Test
    fun `legacy uncertainty constructors preserve status cause message and unproven classification`() {
        val cause = IllegalStateException("fixture private payload")
        val explicit = TransactionCompletionUncertainException(201, cause)
        val defaultCause = TransactionCompletionUncertainException(202)
        assertThat(explicit.status).isEqualTo(201)
        assertThat(explicit.cause).isSameAs(cause)
        assertThat(explicit.message).isEqualTo("transaction-service completion is unproven (HTTP 201)")
        assertThat(explicit.completionFailure).isEqualTo(TransactionCompletionFailure.UNPROVEN)
        assertThat(defaultCause.status).isEqualTo(202)
        assertThat(defaultCause.cause).isNull()
        assertThat(defaultCause.completionFailure).isEqualTo(TransactionCompletionFailure.UNPROVEN)
    }

    @Test
    fun `listing has no mutation completion requirement`() {
        val response = response(null, 200)
        val page = TransactionPage(
            data = listOf("FAILED", "PENDING", "COMPLETED").map {
                TransactionResponse(id = id, status = it)
            },
        )
        every { response.readEntity(TransactionPage::class.java) } returns page
        every { api.listTransactions(id, 20, null) } returns Uni.createFrom().item(response)
        val result = client.list(id, 20, null).await().indefinitely() as TransactionCallOutcome.HttpSuccess
        assertThat(result.body).isSameAs(page)
        assertThat(result.body!!.data!!.map { it.status }).containsExactly("FAILED", "PENDING", "COMPLETED")
        verify(exactly = 1) { response.close() }
    }
}
