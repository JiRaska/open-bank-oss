// SPDX-License-Identifier: Apache-2.0
package com.openbank.sepainstant.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.domain.money.Money
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.sepainstant.infrastructure.client.SettlementAdapter
import com.openbank.sepainstant.infrastructure.client.TransactionServiceClient
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** #13225: the actual module REST client and caller, with the default response mapper intact. */
@QuarkusTest
@TestProfile(TransactionConflictCallerIT.Profile::class)
@QuarkusTestResource(SctInstBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_it")],
)
class TransactionConflictCallerIT {
    class Profile : QuarkusTestProfile {
        override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(Stub::class.java))
    }

    class Stub : QuarkusTestResourceLifecycleManager {
        private var server: HttpServer? = null
        override fun start(): Map<String, String> {
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.createContext("/token") { exchange ->
                exchange.requestBody.readAllBytes()
                val bytes =
                    """{"access_token":"synthetic-conflict-token","token_type":"Bearer","expires_in":300}"""
                        .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
            http.createContext("/api/v1/transactions") { exchange ->
                val request = jacksonObjectMapper().readTree(
                    exchange.requestBody,
                ) as com.fasterxml.jackson.databind.node.ObjectNode
                keys += request.path("idempotencyKey").asText()
                request.put("id", TRANSACTION_ID)
                request.put("status", "COMPLETED")
                val bytes = if (status == 409) {
                    """{"code":"CONCURRENT_UPDATE"}""".toByteArray()
                } else {
                    request.toString().toByteArray()
                }
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
            http.start()
            server = http
            val base = "http://127.0.0.1:${http.address.port}"
            return mapOf(
                "quarkus.rest-client.transaction-service.url" to base,
                "quarkus.oidc-client.m2m.enabled" to "true",
                "quarkus.oidc-client.m2m.auth-server-url" to base,
                "quarkus.oidc-client.m2m.discovery-enabled" to "false",
                "quarkus.oidc-client.m2m.token-path" to "/token",
                "quarkus.oidc-client.m2m.client-id" to "synthetic-caller",
                "quarkus.oidc-client.m2m.credentials.secret" to "synthetic-only",
            )
        }
        override fun stop() {
            server?.stop(0)
        }
        companion object {
            const val TRANSACTION_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

            @Volatile var status = 201
            val keys = CopyOnWriteArrayList<String>()
        }
    }

    @Inject @RestClient
    lateinit var client: TransactionServiceClient

    @Test
    fun `raw module client retains its throwing default 409 mapper`() {
        Stub.status = 409
        val observed = java.util.concurrent.atomic.AtomicInteger()
        val request = com.openbank.clients.transaction.model.InitiateTransactionRequest(
            idempotencyKey = "raw-mapper-control",
            type = "DEBIT",
            amount = java.math.BigDecimal.ONE,
            currencyCode = "EUR",
            valueDate = java.time.LocalDate.of(2026, 7, 13),
        )
        VertxContextSupport.subscribeAndAwait {
            client.initiateTransaction(request)
                .onFailure(jakarta.ws.rs.WebApplicationException::class.java)
                .invoke { failure ->
                    val response = (failure as jakarta.ws.rs.WebApplicationException).response
                    observed.set(response.status)
                    response.close()
                }
                .onFailure(jakarta.ws.rs.WebApplicationException::class.java).recoverWithNull()
        }
        assertThat(observed.get()).isEqualTo(409)
        assertThat(Stub.keys).containsExactly("raw-mapper-control")
    }

    @BeforeEach
    fun reset() {
        Stub.keys.clear()
    }

    @Inject lateinit var adapter: SettlementAdapter

    @Test
    fun `real 409 refuses settlement and 201 preserves its original key`() {
        val input = payment()
        Stub.status = 409
        assertThatThrownBy {
            VertxContextSupport.subscribeAndAwait<com.openbank.sepainstant.application.port.out.SettlementOutcome> {
                adapter.settle(input)
            }
        }
            .isInstanceOf(com.openbank.sepainstant.application.port.out.SettlementUnavailableException::class.java)
            .hasRootCauseInstanceOf(
                com.openbank.sepainstant.application.port.out.SettlementUnavailableException::class.java,
            )
            .hasRootCauseMessage("Unexpected HTTP 409 from transaction-service")
        assertThat(Stub.keys).containsExactly(
            "sct-inst-settlement-${input.id}",
            "sct-inst-settlement-${input.id}",
            "sct-inst-settlement-${input.id}",
        )
        Stub.keys.clear()
        Stub.status = 201
        assertThat(VertxContextSupport.subscribeAndAwait { adapter.settle(input) }.settled).isTrue()
        assertThat(Stub.keys).containsExactly("sct-inst-settlement-${input.id}")
    }
    private val fixedNow = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC)

    private fun payment(id: Long = 42L) = SctInstPayment(
        id = id,
        idempotencyKey = "idem-settle-1",
        status = SctInstStatus.PROCESSING,
        debtorAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob",
        creditorBic = "BNPAFRPPXXX",
        amount = Money.of("99.50", "EUR"),
        remittanceInfo = "Test",
        endToEndId = "E2E-SETTLE-1",
        executionTimeoutAt = null,
        settledAt = null,
        recalledAt = null,
        recallReason = null,
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )
}
