// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Stands in for `openbank-document-service` over real HTTP (ADR-0248 #3) so
 * [com.openbank.sepa.infrastructure.client.DocumentPreviewAdapter]'s two real REST-client calls
 * (list templates, then preview) run for real — the path
 * [com.openbank.sepa.infrastructure.client.DocumentPreviewAdapterTest] mocks away.
 */
class DocumentServiceWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        server = WireMockServer(options().dynamicPort())
        server.start()

        server.stubFor(
            post(urlEqualTo("/token")).willReturn(
                okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}"""),
            ),
        )
        stubPublishedTemplate()

        val base = server.baseUrl()
        return mapOf(
            "quarkus.rest-client.document-service.url" to base,
            "quarkus.oidc-client.enabled" to "true",
            "quarkus.oidc-client.auth-server-url" to base,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to "/token",
            "quarkus.oidc-client.client-id" to "openbank-services",
            "quarkus.oidc-client.credentials.secret" to "test-secret",
            "quarkus.oidc-client.grant.type" to "client",
        )
    }

    override fun stop() {
        if (isStarted()) server.stop()
    }

    companion object {
        lateinit var server: WireMockServer

        fun isStarted() = ::server.isInitialized

        const val TEMPLATES_PATH = "/api/v1/documents/templates"
        const val PREVIEW_PATH = "/api/v1/documents/templates/preview"

        fun stubPublishedTemplate() {
            server.stubFor(
                get(urlPathEqualTo(TEMPLATES_PATH)).willReturn(
                    okJson(
                        """
                        [
                          {"code":"POTVRZENI_O_PLATBE_EN","bodyHtml":"<p>{{document.status}}</p>",
                           "locale":"en","status":"PUBLISHED"},
                          {"code":"POTVRZENI_O_PLATBE_CS","bodyHtml":"<p>{{document.status}}</p>",
                           "locale":"cs","status":"PUBLISHED"}
                        ]
                        """.trimIndent(),
                    ),
                ),
            )
            server.stubFor(
                post(urlEqualTo(PREVIEW_PATH)).willReturn(
                    okJson("""{"renderedHtml":"<html><body>rendered confirmation</body></html>"}"""),
                ),
            )
        }

        /** No PUBLISHED templates at all — exercises the adapter's fail-closed "not found" path. */
        fun stubNoPublishedTemplate() {
            server.stubFor(get(urlPathEqualTo(TEMPLATES_PATH)).willReturn(okJson("""[]""")))
        }

        /** Proves the payment's own data actually reached the preview request body. */
        fun verifyPreviewRequestContained(fragment: String) {
            server.verify(postRequestedFor(urlEqualTo(PREVIEW_PATH)).withRequestBody(containing(fragment)))
        }
    }
}
