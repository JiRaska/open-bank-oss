// SPDX-License-Identifier: Apache-2.0
package com.openbank.sdd.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.sdd.infrastructure.client.TransactionServiceClient
import com.openbank.sdd.infrastructure.kafka.SddCollectionDebitConsumer
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** #13225: the actual module REST client and caller, with the default response mapper intact. */
@QuarkusTest
@TestProfile(TransactionConflictCallerIT.Profile::class)
@QuarkusTestResource(SddBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sdd.it.PostgresTestResource::class)
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

    @Inject @RestClient
    lateinit var client: TransactionServiceClient
    private val mapper = jacksonObjectMapper()

    @Test
    fun `real 409 and 201 preserve the collection caller contract and key`() {
        val consumer = SddCollectionDebitConsumer(mapper, client)
        for (status in listOf(409, 201)) {
            Stub.keys.clear()
            Stub.status = status
            VertxContextSupport.subscribeAndAwait {
                uni(CoroutineScope(Dispatchers.Unconfined)) { consumer.consume(collectionAuthorisedEvent()) }
            }
            assertThat(Stub.keys).containsExactly("so-sdd-$mandateId-UMR-2026-001-2026-07-13")
        }
    }
    private val mandateId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val accountId = UUID.fromString("00000000-0000-0000-0000-0000000000d2")

    private fun collectionAuthorisedEvent(
        eventType: String = "sdd.collection.authorised.v1",
        amount: String = "2200.00",
        currency: String = "EUR",
    ): String = mapper.writeValueAsString(
        mapOf(
            "eventType" to eventType,
            "mandateId" to mandateId,
            "accountId" to accountId,
            "debtorIban" to "DE89370400440532013001",
            "creditorIdentifier" to "DE98ZZZ09999999999",
            "umr" to "UMR-2026-001",
            "scheme" to "CORE",
            "sequenceType" to "RCUR",
            "amount" to amount.toBigDecimal(),
            "currency" to currency,
            "dueDate" to "2026-07-13",
            "occurredAt" to "2026-07-13T10:00:00Z",
        ),
    )
}
