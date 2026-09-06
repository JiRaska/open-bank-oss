// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientTest {

    @Test
    fun `PARTY_HEADER constant matches expected value`() {
        assertThat(UpstreamClient.PARTY_HEADER).isEqualTo("X-Customer-Party-Id")
    }

    /**
     * Regression guard for the [UpstreamClient.getRaw] binary-safety fix: the body must be read as a
     * [ByteArray] and handed to JAX-RS verbatim. The payload below is deliberately NOT valid UTF-8
     * (lone 0xFF/0x80/0xFE bytes), so the previous `BodyHandlers.ofString()` path would have decoded
     * it through the JVM default charset and re-encoded it — silently corrupting the bytes. Serving it
     * over a real (JDK) HTTP server exercises the actual read path end to end.
     */
    @Test
    fun `getRaw returns the upstream bytes unchanged for a binary body`() {
        // A minimal "PDF" whose bytes include sequences that are invalid UTF-8 and would be mangled
        // by a String round-trip (0xFF, 0xFE, 0x80, 0x00, 0xC3 0x28).
        val pdfBytes = byteArrayOf(
            0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, // "%PDF-1.7"
            0x0A, 0xFF.toByte(), 0xFE.toByte(), 0x00, 0x80.toByte(), 0xC3.toByte(), 0x28,
        )

        withServer(
            tokenResponse = """{"access_token":"test-token","expires_in":300}""",
            docBytes = pdfBytes,
            docContentType = "application/pdf",
        ) { client, baseUrl, _ ->
            val response = client.getRaw("$baseUrl/statements/s1/document", "party-1", "*/*")

            assertThat(response.status).isEqualTo(200)
            assertThat(response.mediaType.toString()).isEqualTo("application/pdf")
            // The entity must be the raw ByteArray, byte-for-byte identical to what the upstream sent.
            assertThat(response.entity).isInstanceOf(ByteArray::class.java)
            assertThat(response.entity as ByteArray).isEqualTo(pdfBytes)
        }
    }

    /** Text bodies (camt.053 XML, MT940) are unaffected by the ByteArray switch — verify round-trip. */
    @Test
    fun `getRaw preserves a text body and its content type`() {
        val xml = "<?xml version=\"1.0\"?><Document>statement</Document>"
        withServer(
            tokenResponse = """{"access_token":"test-token","expires_in":300}""",
            docBytes = xml.toByteArray(Charsets.UTF_8),
            docContentType = "application/xml",
        ) { client, baseUrl, _ ->
            val response = client.getRaw("$baseUrl/statements/s1/document", "party-1", "*/*")

            assertThat(response.status).isEqualTo(200)
            assertThat(response.mediaType.toString()).isEqualTo("application/xml")
            assertThat(String(response.entity as ByteArray, Charsets.UTF_8)).isEqualTo(xml)
        }
    }

    @Test
    fun `get sends the bearer token, party header and Accept json to the upstream`() {
        withServer { client, baseUrl, requests ->
            val response = client.get("$baseUrl/statements", "party-9")

            assertThat(response.status).isEqualTo(200)
            val req = requests.single { it.path == "/statements" }
            assertThat(req.method).isEqualTo("GET")
            assertThat(req.headers["authorization"]).isEqualTo("Bearer test-token")
            assertThat(req.headers["x-customer-party-id"]).isEqualTo("party-9")
            assertThat(req.headers["accept"]).isEqualTo("application/json")
        }
    }

    @Test
    fun `postAnonymous sends a generated Idempotency-Key and no party header`() {
        withServer { client, baseUrl, requests ->
            val response = client.postAnonymous("$baseUrl/parties", """{"name":"Jana"}""")

            assertThat(response.status).isEqualTo(200)
            val req = requests.single { it.path == "/parties" }
            assertThat(req.method).isEqualTo("POST")
            assertThat(req.body).isEqualTo("""{"name":"Jana"}""")
            assertThat(req.headers["idempotency-key"]).isNotBlank()
            assertThat(req.headers).doesNotContainKey("x-customer-party-id")
        }
    }

    @Test
    fun `patch sends no body with the party header`() {
        withServer { client, baseUrl, requests ->
            val response = client.patch("$baseUrl/notifications/1", "party-9")

            assertThat(response.status).isEqualTo(200)
            val req = requests.single { it.path == "/notifications/1" }
            assertThat(req.method).isEqualTo("PATCH")
            assertThat(req.body).isEmpty()
            assertThat(req.headers["x-customer-party-id"]).isEqualTo("party-9")
        }
    }

    @Test
    fun `put forwards the body with the party header`() {
        withServer { client, baseUrl, requests ->
            val response = client.put("$baseUrl/savings-goal", "party-9", """{"target":1000}""")

            assertThat(response.status).isEqualTo(200)
            val req = requests.single { it.path == "/savings-goal" }
            assertThat(req.method).isEqualTo("PUT")
            assertThat(req.body).isEqualTo("""{"target":1000}""")
        }
    }

    @Test
    fun `delete sends the party header and no body`() {
        withServer { client, baseUrl, requests ->
            val response = client.delete("$baseUrl/standing-orders/1", "party-9")

            assertThat(response.status).isEqualTo(200)
            val req = requests.single { it.path == "/standing-orders/1" }
            assertThat(req.method).isEqualTo("DELETE")
        }
    }

    @Test
    fun `post uses the caller-supplied Idempotency-Key when non-blank`() {
        withServer { client, baseUrl, requests ->
            client.post("$baseUrl/payments", "party-9", "{}", idempotencyKey = "client-key-1")

            val req = requests.single { it.path == "/payments" }
            assertThat(req.headers["idempotency-key"]).isEqualTo("client-key-1")
        }
    }

    @Test
    fun `post generates an Idempotency-Key when the caller supplies a blank one`() {
        withServer { client, baseUrl, requests ->
            client.post("$baseUrl/payments", "party-9", "{}", idempotencyKey = "   ")

            val req = requests.single { it.path == "/payments" }
            assertThat(req.headers["idempotency-key"]).isNotBlank().isNotEqualTo("   ")
        }
    }

    @Test
    fun `post preserves upstream idempotency replay evidence`() {
        withServer(
            responseHeaders = mapOf(UpstreamClient.IDEMPOTENCY_REPLAY_HEADER to "true"),
        ) { client, baseUrl, _ ->
            val response = client.post("$baseUrl/reservations", "party-9", "{}", "stable-key")

            assertThat(response.getHeaderString(UpstreamClient.IDEMPOTENCY_REPLAY_HEADER)).isEqualTo("true")
        }
    }

    @Test
    fun `post with extraHeaders forwards them and applies them after the standard headers`() {
        withServer { client, baseUrl, requests ->
            client.post(
                "$baseUrl/cards",
                "party-9",
                "{}",
                idempotencyKey = "key-1",
                extraHeaders = mapOf("X-Operator-Id" to "op-42"),
            )

            val req = requests.single { it.path == "/cards" }
            assertThat(req.headers["x-operator-id"]).isEqualTo("op-42")
            assertThat(req.headers["x-customer-party-id"]).isEqualTo("party-9")
        }
    }

    @Test
    fun `reuses the cached token across calls instead of re-authenticating every request`() {
        withServer { client, baseUrl, _ ->
            client.get("$baseUrl/a", "party-1")
            client.get("$baseUrl/b", "party-1")

            assertThat(tokenHits.get()).isEqualTo(1)
        }
    }

    @Test
    fun `re-authenticates once the cached token is within the expiry refresh buffer`() {
        withServer(tokenResponse = """{"access_token":"test-token","expires_in":1}""") { client, baseUrl, _ ->
            client.get("$baseUrl/a", "party-1")
            client.get("$baseUrl/b", "party-1")

            assertThat(tokenHits.get()).isEqualTo(2)
        }
    }

    @Test
    fun `rejects a disallowed host without making any network call and degrades to a 502`() {
        withServer { client, _, requests ->
            val response = client.get("http://evil.example.com/steal", "party-1")

            assertThat(response.status).isEqualTo(502)
            assertThat(response.entity as String).contains("upstream unavailable")
            assertThat(requests).isEmpty()
        }
    }

    @Test
    fun `degrades to a 502 JSON error when the upstream connection fails`() {
        withServer { client, _, _ ->
            // Nothing listens on this port on loopback — an allowed host per the SSRF allowlist,
            // but the connection itself fails, exercising the outer catch block rather than validatedUri.
            val response = client.get("http://127.0.0.1:1/unreachable", "party-1")

            assertThat(response.status).isEqualTo(502)
            assertThat(response.mediaType.toString()).isEqualTo("application/json")
            assertThat(response.entity as String).contains("upstream unavailable")
        }
    }

    // --- test harness ------------------------------------------------------------------

    data class CapturedRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val tokenHits = AtomicInteger(0)

    /**
     * Spins up a throwaway JDK HTTP server (HTTP/1.1, no extra test deps) that answers the
     * client_credentials token fetch and echoes every other request into the captured-request
     * list handed to [block], and tears the server down afterwards.
     */
    private fun withServer(
        tokenResponse: String = """{"access_token":"test-token","expires_in":300}""",
        docBytes: ByteArray? = null,
        docContentType: String = "application/json",
        responseHeaders: Map<String, String> = emptyMap(),
        block: (UpstreamClient, String, List<CapturedRequest>) -> Unit,
    ) {
        tokenHits.set(0)
        val requests = mutableListOf<CapturedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/realms/openbank/protocol/openid-connect/token") { exchange ->
            tokenHits.incrementAndGet()
            respond(exchange, 200, "application/json", tokenResponse.toByteArray(Charsets.UTF_8))
        }
        if (docBytes != null) {
            server.createContext("/statements") { exchange ->
                respond(exchange, 200, docContentType, docBytes)
            }
        }
        server.createContext("/") { exchange ->
            synchronized(requests) {
                requests.add(
                    CapturedRequest(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.path,
                        headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() },
                        body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                    ),
                )
            }
            responseHeaders.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            respond(exchange, 200, "application/json", "{}".toByteArray(Charsets.UTF_8))
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val client = UpstreamClient().apply {
                tokenEndpointBase = "$baseUrl/realms/openbank"
                clientId = "openbank-edge"
                clientSecret = "test-secret"
                tlsTrustCertificateFile = java.util.Optional.empty()
                connectTimeoutMs = 2000
                requestTimeoutMs = 2000
            }
            block(client, baseUrl, requests)
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
