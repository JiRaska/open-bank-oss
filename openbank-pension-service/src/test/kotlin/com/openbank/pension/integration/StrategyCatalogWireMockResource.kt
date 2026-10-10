// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.util.UUID

/** Exercises pension's actual OIDC REST client and catalog projection parser over HTTP. */
class StrategyCatalogWireMockResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> {
        server = WireMockServer(options().dynamicPort())
        server.start()
        stubApproved()
        val base = server.baseUrl()
        return mapOf(
            "quarkus.rest-client.product-catalog.url" to base,
            "quarkus.oidc-client.enabled" to "true",
            "quarkus.oidc-client.auth-server-url" to base,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to "/token",
            "quarkus.oidc-client.client-id" to "openbank-pension",
            "quarkus.oidc-client.credentials.secret" to "test-secret",
            "quarkus.oidc-client.grant.type" to "client",
        )
    }

    override fun stop() {
        server.stop()
    }

    companion object {
        lateinit var server: WireMockServer

        private val strategies = listOf(
            "CONSERVATIVE",
            "BALANCED",
            "SUSTAINABLE_BALANCED",
            "DYNAMIC",
            "EQUITY_GLOBAL",
            "LIFECYCLE",
        )

        fun stubApproved(revision: Int = 7, classes: List<String> = listOf("BOND_FUNDS", "EQUITY_FUNDS")) {
            server.resetAll()
            server.stubFor(
                post(urlEqualTo("/token")).willReturn(
                    okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}"""),
                ),
            )
            val offerings = strategies.indices.joinToString(",") { index ->
                """{"id":"${offeringId(index)}"}"""
            }
            server.stubFor(get(urlEqualTo("/api/v2/offerings")).willReturn(okJson("[$offerings]")))
            strategies.forEachIndexed { index, strategy ->
                val id = offeringId(index)
                val revisionId = UUID.nameUUIDFromBytes("$id:$revision".toByteArray())
                val classJson = classes.joinToString(",") { "\"$it\"" }
                val body = """{"id":"$revisionId","offeringId":"$id","number":$revision,
                    "schemaRef":{"id":"org.openbank.retirement.pension-savings","version":2},
                    "state":"PUBLISHED","makerId":"maker","checkerId":"checker",
                    "reason":"reviewed composition","contentHash":"${"a".repeat(64)}",
                    "content":{"attributes":{"productLine":"DIP","jurisdictionPackId":"CZ/DIP",
                    "fundStrategy":"$strategy","reviewStatus":"LEGAL_AND_COMMERCIAL_REVIEWED",
                    "instrumentClasses":[$classJson]}}}
                """.trimIndent()
                server.stubFor(get(urlPathEqualTo("/api/v2/products/$id")).willReturn(okJson(body)))
            }
        }

        fun verifyCatalogRead() {
            server.verify(
                getRequestedFor(urlEqualTo("/api/v2/offerings"))
                    .withHeader("Authorization", equalTo("Bearer test-token")),
            )
            server.verify(
                getRequestedFor(urlPathEqualTo("/api/v2/products/${offeringId(1)}"))
                    .withQueryParam("effectiveAt", matching(".+"))
                    .withHeader("Authorization", equalTo("Bearer test-token")),
            )
        }

        fun stubNoApprovedMapping() {
            server.stubFor(get(urlEqualTo("/api/v2/offerings")).atPriority(1).willReturn(okJson("[]")))
        }

        private fun offeringId(index: Int): UUID = UUID.nameUUIDFromBytes("dip-strategy-$index".toByteArray())
    }
}
