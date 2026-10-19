// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.TransactionHttpException
import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import com.openbank.standingorder.infrastructure.client.AccountServiceClient
import com.openbank.standingorder.infrastructure.client.SepaPaymentClient
import com.openbank.standingorder.infrastructure.client.TransactionServiceClient
import com.openbank.standingorder.infrastructure.kafka.StandingOrderDueConsumer
import com.openbank.standingorder.it.PostgresRedisTestResource
import com.openbank.standingorder.it.StubTransactionServiceResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * What the standing-order consumer does with each REAL answer transaction-service can give to the
 * generated client (ADR-0319), over a real HTTP socket and the real Quarkus REST client + Jackson
 * mapper. Only the use case and the account lookup are mocked - they are not under test.
 *
 * Each outcome has a different cost if it is wrong, and the old hand-written client (which returned
 * a raw `Response` and branched on its status) made them all visible in one place:
 *  - a 4xx rejection must be RECORDED as a failed execution (else a refused transfer is neither
 *    confirmed nor failed and the order never advances towards suspension);
 *  - a 5xx / 429 is retried and rethrown, never recorded (the transfer may have booked);
 *  - only a readable COMPLETED transaction may confirm; uncertain completion is retried with the same key.
 */
@QuarkusTest
@QuarkusTestResource(TransactionClientOutcomeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@QuarkusTestResource(StubTransactionServiceResource::class, restrictToAnnotatedClass = true)
class TransactionClientOutcomeIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("standing-order-events-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("standing-order-due-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    @RestClient
    lateinit var transactionClient: TransactionServiceClient

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "quarkus.rest-client.transaction-service.url")
    lateinit var stubUrl: String

    private val orderId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val partyId = UUID.fromString("00000000-0000-0000-0000-0000000000e9")
    private val targetAccountId = UUID.fromString("00000000-0000-0000-0000-0000000000e3")

    private val useCase = mockk<StandingOrderUseCase>()
    private val accountClient = mockk<AccountServiceClient>()

    private fun consumer() = StandingOrderDueConsumer(
        useCase,
        objectMapper,
        mockk<SepaPaymentClient>(),
        accountClient,
        transactionClient,
        Clock.fixed(Instant.parse("2026-07-13T10:00:00Z"), ZoneOffset.UTC),
    ).also {
        val lookup = objectMapper.writeValueAsString(mapOf("id" to targetAccountId, "partyId" to partyId))
        every { accountClient.getByIban(any()) } returns Uni.createFrom().item(Response.ok(lookup).build())
        coEvery { useCase.confirmExecution(orderId) } returns mockk(relaxed = true)
        coEvery { useCase.recordFailure(orderId) } returns mockk(relaxed = true)
    }

    private fun dueEvent(marker: String): String = objectMapper.writeValueAsString(
        mapOf(
            "orderId" to orderId,
            "partyId" to partyId,
            "paymentType" to "INTERNAL",
            "debitAccountId" to UUID.fromString("00000000-0000-0000-0000-0000000000e2"),
            "creditorIban" to "DE89370400440532013000",
            "creditorName" to "Creditor",
            "amountMinorUnits" to 220000L,
            "currency" to "CZK",
            "remittanceInfo" to marker,
            "idempotencyKey" to "so-exec-$orderId-2026-07-13",
            "executionDate" to "2026-07-13",
        ),
    )

    private fun posts(marker: String): Int {
        val res = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$stubUrl/__count?m=$marker")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return res.body().toInt()
    }

    @Test
    fun `a real 422 is a rejection - recorded as a failed execution and NOT retried`(): Unit = runBlocking {
        consumer().consume(dueEvent("stub-422"))

        coVerify(exactly = 1) { useCase.recordFailure(orderId) }
        coVerify(exactly = 0) { useCase.confirmExecution(any()) }
        assertThat(posts("stub-422")).describedAs("a rejection is final - no retry").isEqualTo(1)
    }

    @Test
    fun `a real 503 is retried and rethrown, never recorded as a failure`(): Unit = runBlocking {
        val consumer = consumer()

        assertThatThrownBy { runBlocking { consumer.consume(dueEvent("stub-503")) } }
            .isInstanceOf(TransactionHttpException::class.java)

        assertThat(posts("stub-503")).describedAs("EventRetry default attempts").isEqualTo(3)
        coVerify(exactly = 0) { useCase.recordFailure(any()) }
        coVerify(exactly = 0) { useCase.confirmExecution(any()) }
    }

    @Test
    fun `a real 429 says try again - retried and rethrown, not a rejection`(): Unit = runBlocking {
        val consumer = consumer()

        assertThatThrownBy { runBlocking { consumer.consume(dueEvent("stub-429")) } }
            .isInstanceOf(TransactionHttpException::class.java)

        assertThat(posts("stub-429")).isEqualTo(3)
        coVerify(exactly = 0) { useCase.recordFailure(any()) }
    }

    @Test
    fun `403 and 409 are not business rejections - retried and rethrown, never recorded`(): Unit = runBlocking {
        val consumer = consumer()
        listOf("stub-403", "stub-409").forEach { marker ->
            assertThatThrownBy { runBlocking { consumer.consume(dueEvent(marker)) } }
                .describedAs(marker)
                .isInstanceOf(TransactionHttpException::class.java)
            assertThat(posts(marker)).describedAs(marker).isEqualTo(3)
        }
        coVerify(exactly = 0) { useCase.recordFailure(any()) }
        coVerify(exactly = 0) { useCase.confirmExecution(any()) }
    }

    @Test
    fun `a connection cut with no status is NOT a success - retried and rethrown`(): Unit = runBlocking {
        val consumer = consumer()

        assertThatThrownBy { runBlocking { consumer.consume(dueEvent("stub-drop")) } }
            .isInstanceOf(Exception::class.java)

        assertThat(posts("stub-drop")).isEqualTo(3)
        coVerify(exactly = 0) { useCase.confirmExecution(any()) }
        coVerify(exactly = 0) { useCase.recordFailure(any()) }
    }

    @Test
    fun `a COMPLETED transaction with an unknown field is a success`(): Unit = runBlocking {
        consumer().consume(dueEvent("stub-extra"))

        coVerify(exactly = 1) { useCase.confirmExecution(orderId) }
        coVerify(exactly = 0) { useCase.recordFailure(any()) }
        assertThat(posts("stub-extra")).isEqualTo(1)
    }

    @Test
    fun `an unbindable 2xx body is uncertain and never confirmed`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { consumer().consume(dueEvent("stub-unbindable")) } }
            .isInstanceOf(TransactionCompletionUncertainException::class.java)
        assertThat(posts("stub-unbindable")).describedAs("retried with the same event key").isEqualTo(3)
        coVerify(exactly = 0) { useCase.confirmExecution(any()) }
        coVerify(exactly = 0) { useCase.recordFailure(any()) }
    }
}
