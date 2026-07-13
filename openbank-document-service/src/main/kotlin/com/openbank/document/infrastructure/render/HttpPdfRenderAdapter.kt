// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.openbank.document.application.port.out.PdfRenderPort
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * [PdfRenderPort] HTTP adapter (ADR-0162 D3) over the JDK's own `java.net.http.HttpClient` — no
 * extra HTTP-client dependency needed. Blocking `HttpClient.send` calls are confined to
 * [Dispatchers.IO] rather than using the client's async/`sendAsync` API, which is the simpler of
 * the two options the ADR allows and avoids pulling in `kotlinx-coroutines-jdk8` for `.await()`.
 *
 * Two request shapes, selected by `openbank.render.profile` (see `openbank-document-renderer`'s
 * README for the authoritative contract of both):
 *  - **weasyprint** (default): `POST {weasyprint-url}/render`, `Content-Type: text/html`, the raw
 *    HTML bytes as the body — no multipart, no form fields.
 *  - **gotenberg** (opt-in): `POST {gotenberg-url}/forms/chromium/convert/html`,
 *    `multipart/form-data`, with the HTML as a form file part named `index.html` (Gotenberg
 *    renders whichever part is named `index.html`).
 *
 * Both return raw `application/pdf` bytes on success.
 */
@ApplicationScoped
class HttpPdfRenderAdapter(
    @ConfigProperty(name = "openbank.render.profile", defaultValue = "weasyprint")
    private val profile: String,

    @ConfigProperty(name = "openbank.render.weasyprint-url", defaultValue = "http://localhost:8200")
    private val weasyprintUrl: String,

    @ConfigProperty(name = "openbank.render.gotenberg-url", defaultValue = "http://localhost:3000")
    private val gotenbergUrl: String,
) : PdfRenderPort {

    // Bounded connect + request timeouts: a hung renderer sidecar must fail fast, not block the
    // confined Dispatchers.IO thread indefinitely.
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    override suspend fun htmlToPdf(html: String): ByteArray = withContext(Dispatchers.IO) {
        when (profile.lowercase()) {
            PROFILE_GOTENBERG -> renderViaGotenberg(html)
            else -> renderViaWeasyPrint(html)
        }
    }

    private fun renderViaWeasyPrint(html: String): ByteArray {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$weasyprintUrl/render"))
            .header("Content-Type", "text/html")
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .POST(BodyPublishers.ofByteArray(html.toByteArray(StandardCharsets.UTF_8)))
            .build()
        val response = client.send(request, BodyHandlers.ofByteArray())
        check(response.statusCode() == HTTP_OK) {
            "WeasyPrint render failed: HTTP ${response.statusCode()}"
        }
        return response.body()
    }

    private fun renderViaGotenberg(html: String): ByteArray {
        val boundary = "openbank-boundary-${UUID.randomUUID()}"
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$gotenbergUrl/forms/chromium/convert/html"))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .POST(BodyPublishers.ofByteArray(multipartBody(boundary, html)))
            .build()
        val response = client.send(request, BodyHandlers.ofByteArray())
        check(response.statusCode() == HTTP_OK) {
            "Gotenberg render failed: HTTP ${response.statusCode()}"
        }
        return response.body()
    }

    /**
     * Minimal, dependency-free `multipart/form-data` body carrying the HTML as a single file part
     * named `index.html` — the filename Gotenberg's chromium route looks for as the entry document.
     */
    private fun multipartBody(boundary: String, html: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeLine(line: String) = out.write("$line\r\n".toByteArray(StandardCharsets.UTF_8))
        writeLine("--$boundary")
        writeLine("Content-Disposition: form-data; name=\"files\"; filename=\"index.html\"")
        writeLine("Content-Type: text/html; charset=utf-8")
        writeLine("")
        out.write(html.toByteArray(StandardCharsets.UTF_8))
        writeLine("")
        writeLine("--$boundary--")
        return out.toByteArray()
    }

    private companion object {
        const val PROFILE_GOTENBERG = "gotenberg"
        const val HTTP_OK = 200
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val REQUEST_TIMEOUT_SECONDS = 30L
    }
}
