// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.libs.domain.money.Money
import com.openbank.sepa.application.port.out.ReversalUnavailableException
import com.openbank.sepa.application.port.out.SettlementOutcome
import com.openbank.sepa.application.port.out.SettlementUnavailableException
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.infrastructure.client.ReversalAdapter
import com.openbank.sepa.infrastructure.client.SettlementAdapter
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.vertx.core.Vertx
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * #13224: sepa-payment's transaction-service calls run on Temporal activity threads, which carry no
 * Vert.x context. They used to mint the bearer by hand because the OIDC client filter was believed to
 * lose it there. This drives the REAL generated client, the REAL named `m2m` oidc-client (against a stub
 * token endpoint) and the REAL adapters from a plain non-Vert.x thread, through the same wrapper every
 * activity uses (`VertxContextSupport.subscribeAndAwait`, `SepaPaymentActivitiesImpl.runOnVertxContext`),
 * and asserts what reaches the wire: the m2m bearer, minted by the filter. It also drives real HTTP
 * 409 / 202 / non-final answers through the real Quarkus client (#13217, #13225).
 */
@QuarkusTest
@TestProfile(SepaTransactionClientWorkerThreadIT.OidcEnabled::class)
@QuarkusTestResource(SepaPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
class SepaTransactionClientWorkerThreadIT {

    /** Enables the oidc-client (off in %test) against the stub; drops the default-client stub producer. */
    class OidcEnabled : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.oidc-client.enabled" to "true",
            "quarkus.arc.exclude-types" to "com.openbank.sepa.integration.TestOidcClientProducer",
        )

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
            listOf(QuarkusTestProfile.TestResourceEntry(Stub::class.java))
    }

    class Stub : QuarkusTestResourceLifecycleManager {
        private var server: HttpServer? = null

        override fun start(): Map<String, String> {
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.createContext("/token") { exchange ->
                exchange.requestBody.readAllBytes()
                val bytes = """{"access_token":"$M2M_TOKEN","token_type":"Bearer","expires_in":300}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
            http.createContext("/api/v1/transactions") { exchange ->
                exchange.requestBody.readAllBytes()
                authorizations += exchange.requestHeaders.getFirst("Authorization") ?: "<none>"
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
                exchange.close()
            }
            http.start()
            server = http
            val base = "http://127.0.0.1:${http.address.port}"
            return listOf("", "m2m.").flatMap { client ->
                listOf(
                    "quarkus.oidc-client.${client}auth-server-url" to base,
                    "quarkus.oidc-client.${client}discovery-enabled" to "false",
                    "quarkus.oidc-client.${client}token-path" to "/token",
                )
            }.toMap() + mapOf("quarkus.rest-client.transaction-service.url" to base)
        }

        override fun stop() {
            server?.stop(0)
        }

        companion object {
            const val M2M_TOKEN = "stub-m2m-token"
            val authorizations = CopyOnWriteArrayList<String>()

            @Volatile var status = 201

            @Volatile var body = "{}"
        }
    }

    @Inject
    lateinit var settlement: SettlementAdapter

    @Inject
    lateinit var reversal: ReversalAdapter

    private val debtor = UUID.randomUUID()

    @BeforeEach
    fun reset() {
        Stub.authorizations.clear()
    }

    private fun payment() = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-worker-thread",
        type = SepaPaymentType.SCT,
        status = SepaPaymentStatus.PROCESSING,
        debtorAccountId = debtor,
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Debtor",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob Creditor",
        creditorBic = "BNPAFRPPXXX",
        amount = Money.of(BigDecimal("12.34"), "EUR"),
        remittanceInfo = "Invoice 1",
        endToEndId = "E2E-WORKER",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = Instant.now(),
        completedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun completedBody(id: UUID, status: String = "COMPLETED", type: String = "TRANSFER") =
        """{"id":"$id","status":"$status","type":"$type","sourceAccountId":"$debtor","amount":12.34,""" +
            """"currencyCode":"EUR","rail":"SEPA_CT","instructionType":"ONE_OFF","valueDate":"2026-01-20"}"""

    /**
     * Runs [block] the way a Temporal activity does: on a plain thread the SDK owns (no Vert.x context),
     * through the activity wrapper that hands the suspend body a duplicated Vert.x context.
     */
    private fun <T> onWorkerThread(block: suspend () -> T): T {
        val result = CompletableFuture<T>()
        Thread({
            try {
                check(Vertx.currentContext() == null) { "the worker thread must not carry a Vert.x context" }
                result.complete(
                    VertxContextSupport.subscribeAndAwait {
                        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
                    },
                )
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }, "temporal-activity-like-worker").start()
        return try {
            result.get(30, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun failureOf(block: suspend () -> Any): Throwable =
        runCatching { onWorkerThread(block) }.exceptionOrNull() ?: error("call unexpectedly succeeded")

    @Test
    fun `settlement on a Temporal-like worker thread carries the m2m bearer minted by the filter`() {
        val txId = UUID.randomUUID()
        Stub.status = 201
        Stub.body = completedBody(txId)

        val outcome: SettlementOutcome = onWorkerThread { settlement.settle(payment()) }

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
        assertThat(Stub.authorizations).containsExactly("Bearer ${Stub.M2M_TOKEN}")
    }

    @Test
    fun `reversal on a Temporal-like worker thread carries the m2m bearer minted by the filter`() {
        val reversalId = UUID.randomUUID()
        Stub.status = 200
        Stub.body = completedBody(reversalId, type = "REVERSAL")

        val outcome = onWorkerThread { reversal.reverseTransaction(UUID.randomUUID(), "sepa-reversal-x", "AC04") }

        assertThat(outcome.reversed).isTrue()
        assertThat(outcome.reversalTransactionId).isEqualTo(reversalId)
        assertThat(Stub.authorizations).containsExactly("Bearer ${Stub.M2M_TOKEN}")
    }

    @Test
    fun `a real HTTP 409 is neither a settlement nor a reversal, and is not retried`() {
        Stub.status = 409
        Stub.body = """{"code":"CONCURRENT_UPDATE"}"""

        assertThat(failureOf { settlement.settle(payment()) })
            .isInstanceOf(SettlementUnavailableException::class.java).hasMessageContaining("409")
        assertThat(failureOf { reversal.reverseTransaction(UUID.randomUUID(), "sepa-reversal-y", "AC04") })
            .isInstanceOf(ReversalUnavailableException::class.java).hasMessageContaining("409")
        // A definitive answer aborts the retry: one request per call, not three.
        assertThat(Stub.authorizations).hasSize(2)
    }

    @Test
    fun `a real 202 with a COMPLETED body is not settled`() {
        Stub.status = 202
        Stub.body = completedBody(UUID.randomUUID())

        assertThat(failureOf { settlement.settle(payment()) })
            .isInstanceOf(SettlementUnavailableException::class.java).hasMessageContaining("Unexpected HTTP 202")
    }

    @Test
    fun `a real PENDING 201 is completion-uncertain, retried under the same key, and never settled`() {
        Stub.status = 201
        Stub.body = completedBody(UUID.randomUUID(), status = "PENDING")

        val failure = failureOf { settlement.settle(payment()) }

        assertThat(failure).isInstanceOf(SettlementUnavailableException::class.java)
        assertThat(generateSequence(failure) { it.cause }.any { it is TransactionCompletionUncertainException })
            .isTrue()
        // Uncertain is retried (maxRetries = 2) - safe only because every attempt carries the same key.
        assertThat(Stub.authorizations).hasSize(3)
    }

    @Test
    fun `a real COMPLETED 201 for a different booking is not settled`() {
        Stub.status = 201
        Stub.body = completedBody(UUID.randomUUID(), type = "DEBIT")

        assertThat(failureOf { settlement.settle(payment()) })
            .isInstanceOf(SettlementUnavailableException::class.java).hasMessageContaining("does not match")
    }
}
