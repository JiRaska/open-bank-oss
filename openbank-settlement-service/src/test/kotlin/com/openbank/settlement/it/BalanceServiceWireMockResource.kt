// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.it

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.util.UUID

/**
 * Stands in for `openbank-balance-service` over **real HTTP** so the reversal adapters' REST-client
 * calls actually leave the process (issue #6037). This is the part a mocked port cannot establish:
 * a unit test that mocks `ReverseDebitPort` proves the activity calls *something*, not that a
 * money movement was ever addressed to balance-service.
 *
 * Also stubs an OIDC token endpoint, because both REST clients carry
 * `OidcClientRequestReactiveFilter` and would otherwise dial the real realm to mint a bearer token.
 * Same shape as sepa-payment's `DocumentServiceWireMockResource`.
 */
class BalanceServiceWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        server = WireMockServer(options().dynamicPort())
        server.start()

        server.stubFor(
            post(urlEqualTo("/token")).willReturn(
                okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}"""),
            ),
        )
        stubMovementsAccepted()

        val base = server.baseUrl()
        return mapOf(
            "quarkus.rest-client.balance-api.url" to base,
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

        private const val CREDIT_PATH = "/api/v1/balances/[^/]+/credit"
        private const val DEBIT_PATH = "/api/v1/balances/[^/]+/debit"

        fun reset() {
            server.resetRequests()
            server.resetMappings()
            server.stubFor(
                post(urlEqualTo("/token")).willReturn(
                    okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}"""),
                ),
            )
            stubMovementsAccepted()
        }

        /** Both movement verbs succeed, echoing a balance back. */
        fun stubMovementsAccepted() {
            val body = """
                {"accountId":"00000000-0000-0000-0000-000000000001","currency":"CZK",
                 "availableBalance":0.00,"currentBalance":0.00}
            """.trimIndent()
            server.stubFor(post(urlPathMatching(CREDIT_PATH)).willReturn(okJson(body)))
            server.stubFor(post(urlPathMatching(DEBIT_PATH)).willReturn(okJson(body)))
        }

        /**
         * The payee has already spent the credited funds: balance-service's overdraft guard
         * (`Balance.applyDebit`) refuses the counter-debit with 422. No retry resolves this.
         */
        fun stubDebitRefusedInsufficientFunds() {
            server.stubFor(
                post(urlPathMatching(DEBIT_PATH)).willReturn(
                    com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"title":"Insufficient funds","status":422}"""),
                ),
            )
        }

        fun creditRequestsTo(accountId: UUID): RequestPatternBuilder =
            postRequestedFor(urlEqualTo("/api/v1/balances/$accountId/credit"))

        fun debitRequestsTo(accountId: UUID): RequestPatternBuilder =
            postRequestedFor(urlEqualTo("/api/v1/balances/$accountId/debit"))
    }
}
