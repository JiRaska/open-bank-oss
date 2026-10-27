// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Base64
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
 * The stub answers EVERY product id with a valid ACTIVE product, not just this test's one: its
 * config map leaks `quarkus.rest-client.product-catalog-api.url` into the shared test-JVM
 * config (no per-class isolation exists without a @TestProfile, and a profile boots a second
 * full app context — which OOM'd the CI runner's test JVM), so every later catalog call in the
 * suite lands here, and it must read as a working catalog, not a 404 — the earlier single-id
 * stub produced deterministic 'product does not exist' failures in AccountApiIT.
 */
@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_it")],
)
@QuarkusTestResource(ProductCatalogHostHeaderIT.CatalogStub::class)
class ProductCatalogHostHeaderIT {

    @Inject
    @RestClient
    lateinit var client: ProductCatalogClient

    @Inject
    @RestClient
    lateinit var revisionClient: ProductCatalogRevisionClient

    @Test
    fun `the configured Host override reaches the wire`(): Unit = runBlocking {
        val response = client.getById(PRODUCT_ID).awaitSuspending()
        assertThat(response.code).isEqualTo("BEZNY_UCET")

        val hosts = CatalogStub.receivedHosts()
        assertThat(hosts).contains(EXPECTED_HOST)
        assertThat(CatalogStub.receivedAuthorizations()).contains("Bearer account-catalog-token")
        assertThat(CatalogStub.receivedTokenClients()).contains("openbank-account")
    }

    @Test
    fun `published revision read uses the account owned bearer`(): Unit = runBlocking {
        val response = revisionClient
            .getPublishedRevisionForLegacyProduct(PRODUCT_ID, "2026-10-28T00:00:00Z")
            .awaitSuspending()
        assertThat(response.state).isEqualTo("PUBLISHED")
        assertThat(CatalogStub.receivedRevisionAuthorizations()).contains("Bearer account-catalog-token")
    }

    class CatalogStub : QuarkusTestResourceLifecycleManager {
        companion object {
            private val hosts = CopyOnWriteArrayList<String>()
            private val authorizations = CopyOnWriteArrayList<String>()
            private val tokenClients = CopyOnWriteArrayList<String>()
            private val revisionAuthorizations = CopyOnWriteArrayList<String>()
            private lateinit var http: HttpServer

            fun receivedHosts(): List<String> = hosts
            fun receivedAuthorizations(): List<String> = authorizations
            fun receivedTokenClients(): List<String> = tokenClients
            fun receivedRevisionAuthorizations(): List<String> = revisionAuthorizations
        }

        override fun start(): Map<String, String> {
            hosts.clear()
            authorizations.clear()
            tokenClients.clear()
            revisionAuthorizations.clear()
            http = HttpServer.create(InetSocketAddress("127.0.0.1", STUB_PORT), 0)
            http.createContext("/token") { ex ->
                val form = ex.requestBody.bufferedReader().use { it.readText() }
                val basic = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Basic ")
                val basicClient = basic?.let { String(Base64.getDecoder().decode(it)).substringBefore(':') }
                val clientId = form.split("&")
                    .firstOrNull { it.startsWith("client_id=") }
                    ?.substringAfter("=")
                    ?: basicClient.orEmpty()
                tokenClients += clientId
                val token = if (clientId == "openbank-account") "account-catalog-token" else "shared-token"
                val body = """{"access_token":"$token","token_type":"Bearer","expires_in":300}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            // Wildcard route: ANY product id gets a valid ACTIVE product. The class doc explains
            // why a single-id stub is a trap here — this is the only shape that is safe against
            // the config-map leak that redirects the rest of the suite to this stub.
            http.createContext("/api/v1/products") { ex ->
                hosts += ex.requestHeaders.getFirst("Host") ?: ""
                authorizations += ex.requestHeaders.getFirst("Authorization") ?: ""
                val requestedId = ex.requestURI.path.substringAfterLast('/')
                val currency = if (requestedId == EUR_PRODUCT_ID) "EUR" else "CZK"
                val body =
                    """{"id":"$requestedId","code":"BEZNY_UCET","status":"ACTIVE","currency":"$currency"}"""
                        .toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            http.createContext("/api/v2/legacy-products") { ex ->
                revisionAuthorizations += ex.requestHeaders.getFirst("Authorization") ?: ""
                val body = """{"id":"22222222-2222-2222-2222-222222222222","offeringId":"33333333-3333-3333-3333-333333333333","number":1,"state":"PUBLISHED"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            http.executor = Executors.newSingleThreadExecutor()
            http.start()
            return mapOf(
                "quarkus.rest-client.product-catalog-api.url" to "http://127.0.0.1:$STUB_PORT",
                "product-catalog-api.host-override" to EXPECTED_HOST,
                "quarkus.oidc-client.m2m.auth-server-url" to "http://127.0.0.1:$STUB_PORT",
                "quarkus.oidc-client.m2m.discovery-enabled" to "false",
                "quarkus.oidc-client.m2m.token-path" to "/token",
                "quarkus.oidc-client.auth-server-url" to "http://127.0.0.1:$STUB_PORT",
                "quarkus.oidc-client.discovery-enabled" to "false",
                "quarkus.oidc-client.token-path" to "/token",
                "quarkus.oidc.auth-server-url" to "http://127.0.0.1:$STUB_PORT",
            )
        }

        override fun stop() {
            http.stop(0)
        }
    }

    companion object {
        private const val EXPECTED_HOST = "product-catalog.accounts.svc"
        private const val STUB_PORT = 18106
        private const val PRODUCT_ID = "11111111-1111-1111-1111-111111111111"
        private const val EUR_PRODUCT_ID = "00000000-2222-0000-0000-000000000002"
    }
}
