// SPDX-License-Identifier: Apache-2.0
package com.openbank.swift.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import com.openbank.swift.infrastructure.client.SettlementAdapter
import com.openbank.swift.infrastructure.client.TransactionServiceClient
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** #13225: the actual module REST client and caller, with the default response mapper intact. */
@QuarkusTest
@TestProfile(TransactionConflictCallerIT.Profile::class)
@QuarkusTestResource(SwiftBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
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
    fun `real 409 retains SENT and replayed 201 uses the same key`() {
        val input = message()
        Stub.status = 409
        assertThat(
            VertxContextSupport.subscribeAndAwait {
                uni(CoroutineScope(Dispatchers.Unconfined)) { adapter.settle(input) }
            }.settled,
        ).isFalse()
        assertThat(Stub.keys).containsExactly("swift-settlement-${input.id}")
        Stub.status = 201
        assertThat(
            VertxContextSupport.subscribeAndAwait {
                uni(CoroutineScope(Dispatchers.Unconfined)) { adapter.settle(input) }
            }.transactionId,
        )
            .isEqualTo(UUID.fromString(Stub.TRANSACTION_ID))
        assertThat(Stub.keys).containsExactly("swift-settlement-${input.id}", "swift-settlement-${input.id}")
    }
    private fun message(
        id: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555"),
        accountId: UUID? = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        amountMinorUnits: Long = 1_000_00L,
        currency: String = "EUR",
        valueDate: String = "20260622",
    ) = SwiftMessage(
        id = id,
        idempotencyKey = "test",
        messageType = SwiftMessageType.MT103,
        senderBic = "ABCDEFGH",
        receiverBic = "IJKLMNOP",
        transactionReference = "TRX-001",
        relatedReference = null,
        valueDate = valueDate,
        amount = SwiftMessage.moneyOfMinorUnits(amountMinorUnits, currency),
        orderingCustomerAccount = "DE89370400440532013000",
        orderingCustomerAccountId = accountId,
        orderingCustomerName = "Alice",
        beneficiaryAccount = "GB33BUKB20201555555555",
        beneficiaryName = "Bob",
        remittanceInfo = "Invoice 1",
        chargeCode = "SHA",
        priority = SwiftPriority.NORMAL,
        status = SwiftStatus.SENT,
        rawMt = "<pacs.008/>",
        ackReceivedAt = null,
        rejectionReason = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
