// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

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
        ) { client, baseUrl ->
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
        ) { client, baseUrl ->
            val response = client.getRaw("$baseUrl/statements/s1/document", "party-1", "*/*")

            assertThat(response.status).isEqualTo(200)
            assertThat(response.mediaType.toString()).isEqualTo("application/xml")
            assertThat(String(response.entity as ByteArray, Charsets.UTF_8)).isEqualTo(xml)
        }
    }

    /**
     * Spins up a throwaway JDK HTTP server (HTTP/1.1, no extra test deps) that answers the
     * client_credentials token fetch and serves the document, runs [block] against an
     * [UpstreamClient] wired to it, and tears the server down afterwards.
     */
    private fun withServer(
        tokenResponse: String,
        docBytes: ByteArray,
        docContentType: String,
        block: (UpstreamClient, String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/realms/openbank/protocol/openid-connect/token") { exchange ->
            respond(exchange, 200, "application/json", tokenResponse.toByteArray(Charsets.UTF_8))
        }
        server.createContext("/statements") { exchange ->
            respond(exchange, 200, docContentType, docBytes)
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val client = UpstreamClient().apply {
                tokenEndpointBase = "$baseUrl/realms/openbank"
                clientId = "openbank-edge"
                clientSecret = "test-secret"
                connectTimeoutMs = 5000
                requestTimeoutMs = 5000
            }
            block(client, baseUrl)
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
