// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

// Stands in for every synchronous downstream the PaymentSaga touches over HTTP — the ledger
// service (POST /api/v1/journals), the balance-service cover legs (hold/debit/credit on the
// pockets, ADR-0024/0025), and the Keycloak token endpoint the oidc-client reactive filter
// calls — so the full saga -> HTTP path runs in tests without any live infra beyond the
// per-JVM Postgres/Kafka (issue #578). Before the balance cover legs were stubbed here the
// success path tried to reach a real balance-service at localhost:8103 (refused), failing the
// saga into compensation; the shared compose stack never ran balance either, so this gap was
// latent until per-job isolation removed the noise around it.
class LedgerWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        server = WireMockServer(options().dynamicPort())
        server.start()

        server.stubFor(
            post(urlEqualTo("/token")).willReturn(
                okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}"""),
            ),
        )
        stubLedgerSuccess()
        stubBalanceCoverSuccess()

        val base = server.baseUrl()
        return mapOf(
            "quarkus.rest-client.ledger-service.url" to base,
            "quarkus.rest-client.balance-service.url" to base,
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

        const val JOURNALS_PATH = "/api/v1/journals"

        // balance-service cover endpoints (BalanceCoverRestClient): the saga only places and releases
        // the cover hold now — booked debit/credit moved to the ledger projection (ADR-0039 Phase D-2).
        // Path params are matched by regex so any account/hold UUID stubs.
        private const val HOLDS_PATH = "/api/v1/balances/[^/]+/holds"
        private const val RELEASE_HOLD_PATH = "/api/v1/balances/holds/[^/]+"

        fun stubLedgerSuccess() {
            server.stubFor(
                post(urlEqualTo(JOURNALS_PATH)).willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"id":"b0000000-0000-0000-0000-000000000001",""" +
                                """"transactionId":"00000000-0000-0000-0000-000000000000",""" +
                                """"status":"POSTED"}""",
                        ),
                ),
            )
        }

        fun stubLedgerServerError() {
            server.stubFor(
                post(urlEqualTo(JOURNALS_PATH)).willReturn(
                    aResponse().withStatus(500).withHeader("Content-Type", "application/json").withBody("{}"),
                ),
            )
        }

        // Happy-path stubs for the balance-service cover legs: placeHold/releaseHold echo a hold id.
        fun stubBalanceCoverSuccess() {
            server.stubFor(
                post(urlPathMatching(HOLDS_PATH)).willReturn(
                    okJson("""{"id":"c0000000-0000-0000-0000-000000000001"}"""),
                ),
            )
            server.stubFor(
                delete(urlPathMatching(RELEASE_HOLD_PATH)).willReturn(
                    okJson("""{"id":"c0000000-0000-0000-0000-000000000001"}"""),
                ),
            )
        }
    }
}
