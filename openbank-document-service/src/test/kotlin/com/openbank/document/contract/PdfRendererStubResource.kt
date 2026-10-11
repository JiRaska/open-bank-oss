// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.contract

import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

/**
 * Plays the PDF renderer sidecar (`openbank-document-renderer`, ADR-0162 D3) for the provider
 * replay. `POST /api/v1/documents/render` (pension-service's pact, #12392) runs the REAL
 * template lookup, Handlebars merge, object store and outbox; only the HTML → PDF hop leaves the
 * JVM, and that remote is what this stands in for — the same boundary `BusinessAgreementIT` stubs.
 */
class PdfRendererStubResource : QuarkusTestResourceLifecycleManager {
    private lateinit var http: HttpServer

    override fun start(): Map<String, String> {
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/render") { ex ->
            ex.requestBody.readAllBytes()
            val body = PDDocument().use { doc ->
                doc.addPage(PDPage())
                ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
            }
            ex.responseHeaders.add("Content-Type", "application/pdf")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        http.start()
        return mapOf(
            "openbank.render.profile" to "weasyprint",
            "openbank.render.weasyprint-url" to "http://127.0.0.1:${http.address.port}",
        )
    }

    override fun stop() {
        http.stop(0)
    }
}
