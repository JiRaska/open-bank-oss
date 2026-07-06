// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.gdpr

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Optional
import java.util.UUID

/**
 * Covers [GdprAggregationAdapter] — the GDPR Art. 15 orchestration hop from party-service out to
 * kyc-service (`GET /api/v1/kyc/cases/party/{partyId}`) and card-issuance-service
 * (`GET /api/v1/cards/party/{partyId}`), issue #268 / ADR-0118 §6.
 *
 * Uses a real loopback [HttpServer] rather than mocking [java.net.http.HttpClient] directly — the
 * adapter builds its own HttpClient internally, so the only seam available is the actual socket
 * (same pattern as SanctionsImportServiceTest).
 *
 * The contract under test is "best-effort": a downstream that is unreachable, absent (404), or
 * misconfigured (no base URL) must never fail the export — it degrades to null/empty so the DPO
 * can follow up manually, rather than blocking the data subject's Art. 15 request entirely.
 */
class GdprAggregationAdapterTest {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    private fun serveOnce(path: String, status: Int, body: String): String {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext(path) { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        httpServer.start()
        server = httpServer
        return "http://127.0.0.1:${httpServer.address.port}"
    }

    private fun adapter(kycUrl: String? = null, cardUrl: String? = null) = GdprAggregationAdapter().also {
        it.objectMapper = mapper
        it.kycServiceUrl = Optional.ofNullable(kycUrl)
        it.cardServiceUrl = Optional.ofNullable(cardUrl)
    }

    // ──── fetchKycData ────────────────────────────────────────────────────────

    @Test
    fun `fetchKycData returns the deserialised case when kyc-service responds 200`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val base = serveOnce(
            "/api/v1/kyc/cases/party/$partyId",
            200,
            """{"status":"APPROVED","riskLevel":"LOW"}""",
        )

        val result = adapter(kycUrl = base).fetchKycData(partyId)

        assertThat(result).isNotNull
        assertThat(result!!["status"]).isEqualTo("APPROVED")
        assertThat(result["riskLevel"]).isEqualTo("LOW")
    }

    @Test
    fun `fetchKycData returns null when kyc-service has no case for the party (404)`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val base = serveOnce("/api/v1/kyc/cases/party/$partyId", 404, "")

        val result = adapter(kycUrl = base).fetchKycData(partyId)

        assertThat(result).isNull()
    }

    @Test
    fun `fetchKycData returns null without throwing when the base URL is unset`(): Unit = runBlocking {
        val result = adapter(kycUrl = null).fetchKycData(UUID.randomUUID())

        assertThat(result).isNull()
    }

    @Test
    fun `fetchKycData returns null without throwing when kyc-service is unreachable`(): Unit = runBlocking {
        // Nothing listening on this port — connection refused must degrade gracefully, not throw.
        val result = adapter(kycUrl = "http://127.0.0.1:1").fetchKycData(UUID.randomUUID())

        assertThat(result).isNull()
    }

    // ──── fetchCardData ───────────────────────────────────────────────────────

    @Test
    fun `fetchCardData returns the deserialised card list when card-issuance-service responds 200`(): Unit =
        runBlocking {
            val partyId = UUID.randomUUID()
            val base = serveOnce(
                "/api/v1/cards/party/$partyId",
                200,
                """[{"maskedPan":"**** **** **** 1234","status":"ACTIVE"}]""",
            )

            val result = adapter(cardUrl = base).fetchCardData(partyId)

            assertThat(result).hasSize(1)
            assertThat(result.single()["maskedPan"]).isEqualTo("**** **** **** 1234")
        }

    @Test
    fun `fetchCardData returns an empty list when the party holds no cards`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val base = serveOnce("/api/v1/cards/party/$partyId", 200, "[]")

        val result = adapter(cardUrl = base).fetchCardData(partyId)

        assertThat(result).isEmpty()
    }

    @Test
    fun `fetchCardData returns an empty list without throwing when the base URL is unset`(): Unit = runBlocking {
        val result = adapter(cardUrl = null).fetchCardData(UUID.randomUUID())

        assertThat(result).isEmpty()
    }

    @Test
    fun `fetchCardData returns an empty list without throwing when card-issuance-service errors`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val base = serveOnce("/api/v1/cards/party/$partyId", 500, """{"error":"boom"}""")

        val result = adapter(cardUrl = base).fetchCardData(partyId)

        assertThat(result).isEmpty()
    }
}
