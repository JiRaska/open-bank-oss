// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Proves the KEDA-interceptor routing contract end to end (#668 / ADR-0083 T1 re-entry): with
 * `product-catalog-api.host-override` set, the outgoing request carries that exact `Host`
 * header even though the connection URL points elsewhere (the interceptor proxy in production,
 * the JDK stub here). If a future Quarkus/Vert.x upgrade stops honouring an explicitly set Host
 * header, this test goes red instead of the onboarding path silently degrading again — the
 * interceptor would see the proxy's own host, match no HTTPScaledObject, and 404 every read.
 *
 * Two deliberate choices, both learned the hard way in CI:
 *  - Boots the SHARED document-service stack (PostgresRedisTestResource) so the app
 *    starts exactly like every other IT here — an earlier no-resource boot hung on Flyway
 *    against a nonexistent localhost:5432 and poisoned the shared test JVM.
 *  - The stub is the JDK's built-in HttpServer, NOT WireMock: pulling wiremock-standalone into
 *    this module's test classpath is a needless dependency for a two-route stub.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.document.it.PostgresRedisTestResource::class)
@QuarkusTestResource(ProductCatalogHostHeaderIT.CatalogStub::class)
class ProductCatalogHostHeaderIT {

    @Inject
    @RestClient
    lateinit var client: ProductCatalogClient

    @Test
    fun `the configured Host override reaches the wire`(): Unit = runBlocking {
        val response = client.getById(PRODUCT_ID).awaitSuspending()
        assertThat(response.code).isEqualTo("RAMCOVA_SMLOUVA_CS")

        val hosts = CatalogStub.receivedHosts()
        assertThat(hosts).contains(EXPECTED_HOST)
    }

    class CatalogStub : QuarkusTestResourceLifecycleManager {
        companion object {
            private val hosts = CopyOnWriteArrayList<String>()
            private lateinit var http: HttpServer

            fun receivedHosts(): List<String> = hosts
        }

        override fun start(): Map<String, String> {
            hosts.clear()
            http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.createContext("/token") { ex ->
                val body = """{"access_token":"t","token_type":"Bearer","expires_in":300}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            http.createContext("/api/v1/products/$PRODUCT_ID") { ex ->
                hosts += ex.requestHeaders.getFirst("Host") ?: ""
                val body =
                    """{"id":"$PRODUCT_ID","code":"RAMCOVA_SMLOUVA_CS","name":"R","termsAndConditions":[]}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            http.executor = Executors.newSingleThreadExecutor()
            http.start()
            return mapOf(
                "quarkus.rest-client.product-catalog-api.url" to "http://127.0.0.1:${http.address.port}",
                "product-catalog-api.host-override" to EXPECTED_HOST,
                "quarkus.oidc-client.auth-server-url" to "http://127.0.0.1:${http.address.port}",
                "quarkus.oidc-client.discovery-enabled" to "false",
                "quarkus.oidc-client.token-path" to "/token",
                "quarkus.oidc.auth-server-url" to "http://127.0.0.1:${http.address.port}",
            )
        }

        override fun stop() {
            http.stop(0)
        }
    }

    companion object {
        private const val EXPECTED_HOST = "product-catalog.accounts.svc"
        private const val PRODUCT_ID = "11111111-1111-1111-1111-111111111111"
    }
}
