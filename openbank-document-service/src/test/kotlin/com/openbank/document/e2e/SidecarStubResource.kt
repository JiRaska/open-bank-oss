// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One JDK `HttpServer` stubbing the two sidecars the document lifecycle travels to in this
 * journey — no new dependency, the same approach the module's contract tests take with
 * `PdfRendererStubResource`.
 *
 *  - **WeasyPrint** (`POST /render`): the document-renderer sidecar that turns merged HTML into
 *    PDF bytes (ADR-0162 D3). The stub answers with a minimal but well-formed PDF carrying the
 *    request body's text, so the journey can assert content actually round-tripped.
 *  - **sca-service** (`POST /api/v1/sca/challenges/{id}/consume`): the single-use SCA evidence
 *    spend (RTS Art. 5) a SIGNED decision must pass (ADR-0021). The first consume of a challenge
 *    answers 200; a replay of the same challenge id answers 409 exactly like
 *    `ScaChallengeAlreadyConsumedException` — that single-use property is what the signing
 *    journey's replay decision asserts.
 *
 * Everything is served from one server because the journey overrides both sidecar URLs to it;
 * the routes are disjoint by path.
 */
class SidecarStubResource : QuarkusTestResourceLifecycleManager {

    private lateinit var server: HttpServer

    /** Challenge ids already consumed — the single-use set the stub enforces. */
    private val consumed = ConcurrentHashMap.newKeySet<UUID>()

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (t: Throwable) {
                respond(exchange, 500, "text/plain", "stub: ${t.message}")
            } finally {
                exchange.close()
            }
        }
        server.start()
        val base = "http://localhost:${server.address.port}"
        return mapOf(
            "openbank.render.weasyprint-url" to base,
            "quarkus.rest-client.sca-service.url" to base,
        )
    }

    override fun stop() {
        server.stop(0)
    }

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        when {
            path == "/render" && exchange.requestMethod == "POST" -> renderPdf(exchange)
            path.matches(Regex("/api/v1/sca/challenges/[^/]+/consume")) && exchange.requestMethod == "POST" ->
                consumeChallenge(exchange)
            else -> respond(exchange, 404, "application/json", """{"error":"stub has no route for $path"}""")
        }
    }

    /** Minimal well-formed PDF whose visible text echoes the HTML it was rendered from. */
    private fun renderPdf(exchange: HttpExchange) {
        val html = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val text = html.replace("(", "[").replace(")", "]").take(MAX_PDF_TEXT_CHARS)
        val stream = "BT /F1 10 Tf 10 100 Td (rendered-from-stub: $text) Tj ET"
        val objects = """
            1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
            2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
            3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 144]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj
            4 0 obj<</Length ${stream.toByteArray(Charsets.UTF_8).size}>>stream
            $stream
            endstream endobj
            5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
        """.trimIndent()
        val pdf = buildString {
            append("%PDF-1.4\n")
            var offset = "%PDF-1.4\n".length
            val offsets = mutableListOf<Int>()
            objects.split("\n").filter { it.isNotBlank() }.forEach { line ->
                offsets.add(offset)
                append(line).append("\n")
                offset += line.length + 1
            }
            val xrefOffset = offset
            append("xref\n0 6\n0000000000 65535 f \n")
            offsets.forEach { append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", it)) }
            append("trailer<</Size 6/Root 1 0 R>>\nstartxref\n$xrefOffset\n%%EOF")
        }
        respond(exchange, 200, "application/pdf", pdf)
    }

    /**
     * First consume of a challenge succeeds and marks it spent; every later consume of the same
     * id answers 409 — the RTS Art. 5 single-use rule sca-service enforces with
     * `ScaChallengeAlreadyConsumedException`.
     */
    private fun consumeChallenge(exchange: HttpExchange) {
        val id = UUID.fromString(pathId(exchange.requestURI.path))
        if (!consumed.add(id)) {
            respond(exchange, 409, "application/json", """{"error":"challenge already consumed"}""")
            return
        }
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val partyId = Regex("\"partyId\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: UUID(0L, 0L).toString()
        respond(exchange, 200, "application/json", """{"id":"$id","partyId":"$partyId","status":"CONSUMED"}""")
    }

    private fun pathId(path: String): String = path.substringAfter("/challenges/").substringBefore("/")

    private fun respond(exchange: HttpExchange, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val MAX_PDF_TEXT_CHARS = 120
    }
}
