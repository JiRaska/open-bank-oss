// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Stands in for the two downstream reads every snapshot fans out to — ledger-service's
 * `GET /api/v1/journals/trial-balance` + `GET /api/v1/journals/sub-ledger-balances` and
 * lending-service's `GET /api/v1/lending/loan-book` (ADR-0314 D4) — plus the OIDC token endpoint,
 * because all three clients carry `OidcClientRequestReactiveFilter` and would otherwise dial the
 * real realm to mint the shared `openbank-services` bearer token. Same shape as settlement's and
 * vop's downstream resources: the REST clients, their timeouts and the failure mapping all cross
 * a wire they actually have to.
 *
 * The request journal is the fan-out proof: [requestsTo] counts GETs per path since the last
 * [reset], so a test can assert "the close actually dialled both ledger views with scope=ALL".
 */
class RiskDownstreamWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        server = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        server!!.start()
        server!!.stubFor(
            post(urlEqualTo("/token")).willReturn(
                okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}"""),
            ),
        )
        val base = server!!.baseUrl()
        return mapOf(
            "quarkus.oidc-client.enabled" to "true",
            "quarkus.oidc-client.auth-server-url" to base,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to "/token",
            "quarkus.oidc-client.client-id" to "openbank-services",
            "quarkus.oidc-client.credentials.secret" to "test-secret",
            "quarkus.oidc-client.grant.type" to "client",
            "quarkus.rest-client.ledger-service.url" to base,
            "quarkus.rest-client.lending-service.url" to base,
        )
    }

    override fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        private var server: WireMockServer? = null

        /** Drops all stubs and the request journal, leaving only the token endpoint. */
        fun reset() {
            server?.resetAll()
            server?.stubFor(
                post(urlEqualTo("/token")).willReturn(
                    okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}"""),
                ),
            )
        }

        /**
         * The ledger trial balance for [asOf]. The adapter forces `scope=ALL` (REAL_ONLY would
         * report every synthetic canary posting as a tie-out break against the unscoped
         * sub-ledger), so the stub pins that query parameter — a client that drops it stops
         * matching and the test fails on the wire, not on an assertion.
         */
        fun stubTrialBalance(asOf: String, linesJson: String) {
            server!!.stubFor(
                get(urlPathEqualTo("/api/v1/journals/trial-balance"))
                    .withQueryParam("asOf", equalTo(asOf))
                    .withQueryParam("scope", equalTo("ALL"))
                    .willReturn(
                        okJson("""{"asOf":"$asOf","scope":"ALL","lines":[$linesJson]}"""),
                    ),
            )
        }

        /** The ledger sub-ledger balances for [asOf] (no scope parameter exists on this view). */
        fun stubSubLedgerBalances(asOf: String, balancesJson: String) {
            server!!.stubFor(
                get(urlPathEqualTo("/api/v1/journals/sub-ledger-balances"))
                    .withQueryParam("asOf", equalTo(asOf))
                    .willReturn(
                        okJson("""{"asOf":"$asOf","balances":[$balancesJson]}"""),
                    ),
            )
        }

        /**
         * Lending's loan book for [asOf]. The adapter REFUSES a book whose own asOf disagrees
         * with the requested date (carrying it would tie out against the wrong trial balance),
         * so the stub pins the query parameter too.
         */
        fun stubLoanBook(asOf: String, loansJson: String) {
            server!!.stubFor(
                get(urlPathEqualTo("/api/v1/lending/loan-book"))
                    .withQueryParam("asOf", equalTo(asOf))
                    .willReturn(
                        okJson("""{"asOf":"$asOf","loans":[$loansJson]}"""),
                    ),
            )
        }

        /**
         * Lending answers a book stamped with a DIFFERENT as-of than the requested date: the
         * adapter's presence check must refuse it (a book for another day would tie out against
         * the wrong trial balance).
         */
        fun stubLoanBookMismatched(requestAsOf: String, bookAsOf: String) {
            server!!.stubFor(
                get(urlPathEqualTo("/api/v1/lending/loan-book"))
                    .withQueryParam("asOf", equalTo(requestAsOf))
                    .willReturn(
                        okJson("""{"asOf":"$bookAsOf","loans":[]}"""),
                    ),
            )
        }

        /** The trial-balance view is down: the snapshot must fail closed and persist nothing. */
        fun stubTrialBalanceUnavailable(asOf: String) {
            server!!.stubFor(
                get(urlPathEqualTo("/api/v1/journals/trial-balance"))
                    .withQueryParam("asOf", equalTo(asOf))
                    .willReturn(serviceUnavailable()),
            )
        }

        /** How many GETs [path] received since the last [reset] — the downstream fan-out proof. */
        fun requestsTo(path: String): Int = server!!.countRequestsMatching(
            com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(path)).build(),
        ).count
    }
}
