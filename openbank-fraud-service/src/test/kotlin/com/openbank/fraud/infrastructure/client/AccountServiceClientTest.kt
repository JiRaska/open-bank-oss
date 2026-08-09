// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fraud.infrastructure.client

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.quarkus.oidc.client.OidcClient
import io.quarkus.oidc.client.Tokens
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID

/**
 * Real HTTP round-trip against a local [HttpServer] (no network mock library on this service's
 * classpath) — verifies the party-lookup success path plus the 404/error fail-open contract that
 * [com.openbank.fraud.application.usecase.FraudHoldService] depends on never propagating.
 *
 * `@QuarkusTest`, not a bare unit test: `RestClientBuilder.newBuilder()` resolves its SPI
 * implementation only inside a running Quarkus app (build-time augmentation registers it) — a
 * bare JUnit test throws `IllegalStateException: No RestClientBuilderResolver implementation
 * found!` on Linux CI (it happened to resolve locally on macOS, which masked this).
 */
@QuarkusTest
class AccountServiceClientTest {

    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun clientFor(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): AccountServiceClient {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/v1/accounts") { exchange -> handler(exchange) }
        srv.start()
        server = srv

        val oidcClient = mockk<OidcClient>()
        every { oidcClient.tokens } returns
            Uni.createFrom().item(Tokens("token-123", null, null, null, null, null, null))
        val instance = mockk<Instance<OidcClient>>()
        every { instance.get() } returns oidcClient

        return AccountServiceClient(instance, "http://127.0.0.1:${srv.address.port}")
    }

    @Test
    fun `resolves the partyId from a 200 response`() {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val client = clientFor { exchange ->
            val body = """{"id":"$accountId","partyId":"$partyId"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val result = runBlocking { client.findPartyByAccountId(accountId) }

        assertThat(result).isEqualTo(partyId)
    }

    @Test
    fun `a 404 resolves to null without logging as an error`() {
        val accountId = UUID.randomUUID()
        val client = clientFor { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        val result = runBlocking { client.findPartyByAccountId(accountId) }

        assertThat(result).isNull()
    }

    @Test
    fun `a 500 resolves to null (fail-open, never fails the caller)`() {
        val accountId = UUID.randomUUID()
        val client = clientFor { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }

        val result = runBlocking { client.findPartyByAccountId(accountId) }

        assertThat(result).isNull()
    }

    @Test
    fun `an unreachable server resolves to null (fail-open, never fails the caller)`() {
        // Bind and immediately stop, so the port is (almost certainly) refused on connect.
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.start()
        val port = srv.address.port
        srv.stop(0)

        val oidcClient = mockk<OidcClient>()
        every { oidcClient.tokens } returns
            Uni.createFrom().item(Tokens("token-123", null, null, null, null, null, null))
        val instance = mockk<Instance<OidcClient>>()
        every { instance.get() } returns oidcClient
        val client = AccountServiceClient(instance, "http://127.0.0.1:$port")

        val result = runBlocking { client.findPartyByAccountId(UUID.randomUUID()) }

        assertThat(result).isNull()
    }
}
