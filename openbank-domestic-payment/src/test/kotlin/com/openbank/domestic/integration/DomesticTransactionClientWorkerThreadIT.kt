// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.domestic.application.port.out.SettlementOutcome
import com.openbank.domestic.application.port.out.SettlementUnavailableException
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.domestic.infrastructure.client.SettlementAdapter
import com.openbank.libs.domain.money.Money
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * #13224: domestic-payment's settlement runs on a Temporal activity thread, which carries no Vert.x
 * context, and used to mint the bearer by hand because the OIDC client filter was believed to lose it
 * there. This drives the REAL generated client, the REAL named `m2m` oidc-client (against a stub token
 * endpoint) and the REAL adapter from a plain non-Vert.x thread, through the same wrapper every activity
 * uses (`DomesticPaymentActivitiesImpl.vtx` = `VertxContextSupport.subscribeAndAwait`), and asserts what
 * reaches the wire: the m2m bearer, minted by the filter. It also drives real HTTP 409 / 202 / non-final /
 * mismatched answers through the real Quarkus client (#13217, #13225).
 */
@QuarkusTest
@TestProfile(DomesticTransactionClientWorkerThreadIT.OidcEnabled::class)
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
class DomesticTransactionClientWorkerThreadIT {

    /** Enables the oidc-client (off in %test) against the stub. */
    class OidcEnabled : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("quarkus.oidc-client.enabled" to "true")

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

    private val debtor = UUID.randomUUID()

    @BeforeEach
    fun reset() {
        Stub.authorizations.clear()
    }

    /** EXTERNAL scope: a debit-only booking, so no account-service lookup is involved. */
    private fun payment() = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "worker-thread-it",
        status = DomesticPaymentStatus.SENT_TO_CLEARING,
        debtorAccountId = debtor,
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Alice",
        creditorAccountNumber = "9876543210",
        creditorBankCode = "0100",
        creditorName = "Bob",
        amount = Money.of(BigDecimal("500.00"), "CZK"),
        variableSymbol = "12345",
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = "Worker thread IT",
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.EXTERNAL,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOM-WORKER-001",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun body(id: UUID, status: String = "COMPLETED", currency: String = "CZK") =
        """{"id":"$id","status":"$status","type":"DEBIT","sourceAccountId":"$debtor","amount":500.00,""" +
            """"currencyCode":"$currency","rail":"DOMESTIC","instructionType":"ONE_OFF","valueDate":"2026-01-20"}"""

    /** Runs [block] the way a Temporal activity does: a plain SDK-owned thread, then the activity wrapper. */
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
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun settleFails(): Throwable =
        runCatching { onWorkerThread { settlement.settle(payment()) } }.exceptionOrNull()
            ?: error("settle() unexpectedly succeeded")

    @Test
    fun `settlement on a Temporal-like worker thread carries the m2m bearer minted by the filter`() {
        val txId = UUID.randomUUID()
        Stub.status = 201
        Stub.body = body(txId)

        val outcome: SettlementOutcome = onWorkerThread { settlement.settle(payment()) }

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
        assertThat(Stub.authorizations).containsExactly("Bearer ${Stub.M2M_TOKEN}")
    }

    @Test
    fun `a real HTTP 409 is not a settlement and is not retried`() {
        Stub.status = 409
        Stub.body = """{"code":"CONCURRENT_UPDATE"}"""

        assertThat(settleFails()).isInstanceOf(SettlementUnavailableException::class.java)
            .hasMessageContaining("HTTP 409")
        assertThat(Stub.authorizations).hasSize(1)
    }

    @Test
    fun `a real 202 with a COMPLETED body is not settled`() {
        Stub.status = 202
        Stub.body = body(UUID.randomUUID())

        assertThat(settleFails()).isInstanceOf(SettlementUnavailableException::class.java)
            .hasMessageContaining("HTTP 202")
    }

    @Test
    fun `a real PENDING 201 is completion-uncertain, retried under the same key, and never settled`() {
        Stub.status = 201
        Stub.body = body(UUID.randomUUID(), status = "PENDING")

        val failure = settleFails()

        assertThat(failure).isInstanceOf(SettlementUnavailableException::class.java)
        assertThat(generateSequence(failure) { it.cause }.any { it is TransactionCompletionUncertainException })
            .isTrue()
        assertThat(Stub.authorizations).hasSize(3)
    }

    @Test
    fun `a real COMPLETED 201 for a different booking is not settled`() {
        Stub.status = 201
        Stub.body = body(UUID.randomUUID(), currency = "EUR")

        assertThat(settleFails()).isInstanceOf(SettlementUnavailableException::class.java)
            .hasMessageContaining("does not match")
    }
}
