// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.psd2.infrastructure.client.TppAuthorizationGuard
import com.openbank.psd2.infrastructure.client.TppRegistryRestClient
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Exercises the production REST client, CDI guard interceptors, and eIDAS filter against a local
 * HTTP registry. The X-TPP-ID header is the same identity input used by the existing filter ITs;
 * this test leaves the eIDAS filter enabled and only disables the unrelated OPA sidecar check.
 */
@QuarkusTest
@TestProfile(TppAuthorizationRestClientIT.RealRegistryProfile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    restrictToAnnotatedClass = true,
    initArgs = [ResourceArg(name = "db", value = "openbank_psd2_it")],
)
@QuarkusTestResource(value = LocalTppRegistryResource::class, restrictToAnnotatedClass = true)
class TppAuthorizationRestClientIT {

    @Inject
    @RestClient
    lateinit var registryClient: TppRegistryRestClient

    @ConfigProperty(name = "test.tpp-registry.url")
    lateinit var registryUrl: String

    @Test
    fun `valid denial decodes once and real failures retry then open the circuit`() {
        val directGuard = TppAuthorizationGuard(registryClient)
        listOf(MISSING_AUTHORIZED, NULL_AUTHORIZED, STRING_AUTHORIZED).forEach { tppId ->
            val outcome = runCatching { directGuard.requireAuthorized(tppId, "AISP") }
            // Check the local server first: this proves the injected client made a real HTTP call
            // and prevents a test fake from satisfying the exception assertion.
            assertThat(registryHits(tppId)).isEqualTo(1)
            val error = outcome.exceptionOrNull()
            assertThat(error).isInstanceOf(WebApplicationException::class.java)
            error as WebApplicationException
            assertThat(error.response.status).isEqualTo(403)
        }

        // The bare client call isolates JSON-to-DTO conversion from the guard's 403 handling.
        // If this yields authorized=false, the real REST client decoder treats a missing Kotlin
        // primitive Boolean as its default and the guard must validate the raw JSON shape.
        val decodedMissingAuthorized = registryClient.checkAuthorization(TYPED_MISSING_AUTHORIZED, "AISP")
        assertThat(registryHits(TYPED_MISSING_AUTHORIZED)).isEqualTo(1)
        assertThat(decodedMissingAuthorized.authorized).isFalse()

        val denial = publicRequest(VALID_DENIAL)
        assertThat(denial.statusCode).isEqualTo(401)
        assertThat(denial.jsonPath().getString("tppMessages[0].code")).isEqualTo("CERTIFICATE_INVALID")
        assertThat(registryHits(VALID_DENIAL)).isEqualTo(1)

        val fault = publicRequest(UPSTREAM_FAILURE)
        assertThat(fault.statusCode).isEqualTo(503)
        assertThat(fault.jsonPath().getString("tppMessages[0].code")).isEqualTo("SERVICE_UNAVAILABLE")
        assertThat(registryHits(UPSTREAM_FAILURE)).isEqualTo(3)

        var circuitOpened = false
        var attempts = 0
        while (!circuitOpened && attempts < 12) {
            attempts++
            val before = registryHits(CIRCUIT_FAILURE)
            val response = publicRequest(CIRCUIT_FAILURE)
            assertThat(response.statusCode).isEqualTo(503)
            if (registryHits(CIRCUIT_FAILURE) == before) {
                circuitOpened = true
                break
            }
        }
        assertThat(circuitOpened).isTrue()

        val afterOpen = registryHits(CIRCUIT_FAILURE)
        val openCircuit = publicRequest(CIRCUIT_FAILURE)
        assertThat(openCircuit.statusCode).isEqualTo(503)
        assertThat(openCircuit.jsonPath().getString("tppMessages[0].code")).isEqualTo("SERVICE_UNAVAILABLE")
        assertThat(registryHits(CIRCUIT_FAILURE)).isEqualTo(afterOpen)
    }

    private fun publicRequest(tppId: String) = given()
        .header("X-TPP-ID", tppId)
        .get("/v1/consents/00000000-0000-0000-0000-000000000000/status")

    private fun registryHits(tppId: String): Int {
        val url = URI.create("$registryUrl/__test/hits?tppId=$tppId").toURL()
        val connection = url.openConnection() as HttpURLConnection
        return connection.inputStream.bufferedReader().use { it.readText().toInt() }
    }

    class RealRegistryProfile : QuarkusTestProfile {
        override fun getConfigProfile(): String = "psd2-real-tpp-registry"

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
            "quarkus.config.profile.parent" to "test",
            "quarkus.http.test-port" to "0",
            "quarkus.management.test-port" to "0",
        )
    }

    private companion object {
        const val VALID_DENIAL = "TPP-IT-REAL-UNKNOWN"
        const val MISSING_AUTHORIZED = "TPP-IT-MISSING-AUTHORIZED"
        const val NULL_AUTHORIZED = "TPP-IT-NULL-AUTHORIZED"
        const val STRING_AUTHORIZED = "TPP-IT-STRING-AUTHORIZED"
        const val TYPED_MISSING_AUTHORIZED = "TPP-IT-TYPED-MISSING-AUTHORIZED"
        const val UPSTREAM_FAILURE = "TPP-IT-UPSTREAM-500"
        const val CIRCUIT_FAILURE = "TPP-IT-CIRCUIT-500"
    }
}

/** Local HTTP endpoint that emits the registry's real refusal status and malformed variants. */
class LocalTppRegistryResource : io.quarkus.test.common.QuarkusTestResourceLifecycleManager {

    private lateinit var server: com.sun.net.httpserver.HttpServer

    override fun start(): Map<String, String> {
        hits.clear()
        server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/tpp-registry/check") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').associate { pair ->
                val (key, value) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
            val tppId = query["tppId"].orEmpty()
            hits.computeIfAbsent(tppId) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            val response = when (tppId) {
                "TPP-IT-REAL-UNKNOWN" ->
                    403 to
                        """{"tppId":"$tppId","authorized":false,"roles":[],"reason":"TPP not found in registry"}"""

                "TPP-IT-MISSING-AUTHORIZED" ->
                    403 to
                        """{"tppId":"$tppId","roles":[],"reason":"TPP not found in registry"}"""

                "TPP-IT-NULL-AUTHORIZED" ->
                    403 to
                        """{"tppId":"$tppId","authorized":null,"roles":[],"reason":"TPP not found in registry"}"""

                "TPP-IT-STRING-AUTHORIZED" ->
                    403 to
                        """{"tppId":"$tppId","authorized":"false","roles":[],"reason":"TPP not found in registry"}"""

                "TPP-IT-TYPED-MISSING-AUTHORIZED" ->
                    200 to
                        """{"tppId":"$tppId","roles":[],"reason":"TPP not found in registry"}"""

                else -> 500 to """{"error":"synthetic registry outage"}"""
            }
            respond(exchange, response.first, response.second, "application/json")
        }
        server.createContext("/__test/hits") { exchange ->
            val tppId = exchange.requestURI.rawQuery.orEmpty().substringAfter("tppId=", "")
            val count = hits[tppId]?.get() ?: 0
            respond(exchange, 200, count.toString(), "text/plain")
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        return mapOf(
            "quarkus.rest-client.tpp-registry.url" to baseUrl,
            "test.tpp-registry.url" to baseUrl,
        )
    }

    override fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun respond(
        exchange: com.sun.net.httpserver.HttpExchange,
        status: Int,
        body: String,
        contentType: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        val hits = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    }
}
