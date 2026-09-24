// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Driven against a JDK [HttpServer] on an ephemeral port — no container, no external renderer.
 * The two request shapes are contracts with `openbank-document-renderer`: WeasyPrint takes the raw
 * HTML with `Content-Type: text/html`, Gotenberg takes a multipart part whose FILENAME must be
 * `index.html` (that filename, not the field name, is what its chromium route renders).
 */
class HttpPdfRenderAdapterTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val recordedPaths = mutableListOf<String>()
    private val recordedContentTypes = mutableListOf<String?>()
    private val recordedBodies = mutableListOf<ByteArray>()

    private var status: Int = 200
    private var responseBody: ByteArray = byteArrayOf(0x25, 0x50, 0x44, 0x46) // "%PDF"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            recordedPaths += exchange.requestURI.path
            recordedContentTypes += exchange.requestHeaders.getFirst("Content-Type")
            recordedBodies += exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(status, responseBody.size.toLong())
            exchange.responseBody.use { it.write(responseBody) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun adapter(profile: String) = HttpPdfRenderAdapter(profile, baseUrl, baseUrl)

    @Test
    fun `the default profile posts raw HTML to WeasyPrint's render route`(): Unit = runBlocking {
        val pdf = adapter("weasyprint").htmlToPdf("<p>hello</p>")

        assertThat(pdf).isEqualTo(responseBody)
        assertThat(recordedPaths).containsExactly("/render")
        assertThat(recordedContentTypes).containsExactly("text/html")
        assertThat(String(recordedBodies.single(), StandardCharsets.UTF_8)).isEqualTo("<p>hello</p>")
    }

    @Test
    fun `an unknown profile falls back to WeasyPrint rather than failing`(): Unit = runBlocking {
        adapter("something-else").htmlToPdf("<p>x</p>")

        assertThat(recordedPaths).containsExactly("/render")
    }

    @Test
    fun `the gotenberg profile is matched case-insensitively and posts a multipart body`(): Unit = runBlocking {
        adapter("GOTENBERG").htmlToPdf("<p>hi</p>")

        assertThat(recordedPaths).containsExactly("/forms/chromium/convert/html")
        assertThat(recordedContentTypes.single()).startsWith("multipart/form-data; boundary=")

        val body = String(recordedBodies.single(), StandardCharsets.UTF_8)
        val boundary = recordedContentTypes.single()!!.substringAfter("boundary=")
        assertThat(body).contains("filename=\"index.html\"")
        assertThat(body).contains("<p>hi</p>")
        assertThat(body).startsWith("--$boundary\r\n").endsWith("--$boundary--\r\n")
    }

    @Test
    fun `UTF-8 content is sent as UTF-8 bytes, not mangled to the platform charset`(): Unit = runBlocking {
        adapter("weasyprint").htmlToPdf("<p>Příliš žluťoučký kůň</p>")

        assertThat(String(recordedBodies.single(), StandardCharsets.UTF_8)).contains("Příliš žluťoučký kůň")
    }

    @Test
    fun `a non-200 from WeasyPrint fails loudly with the status code`() {
        status = 500

        assertThatThrownBy { runBlocking { adapter("weasyprint").htmlToPdf("<p>x</p>") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("WeasyPrint render failed: HTTP 500")
    }

    @Test
    fun `a non-200 from Gotenberg fails loudly with the status code`() {
        status = 400

        assertThatThrownBy { runBlocking { adapter("gotenberg").htmlToPdf("<p>x</p>") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Gotenberg render failed: HTTP 400")
    }
}
