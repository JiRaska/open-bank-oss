// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.notFound
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Stands in for BOTH hops of the ADR-0171 §4 name resolution over **real HTTP** — account-service
 * (`GET /api/v1/accounts/iban/{iban}`) and party-service (`GET /api/v1/parties/{id}`) — so the
 * REST-client calls, the `@Retry`/`@CircuitBreaker`/`@Timeout` annotations and the 404-vs-5xx
 * distinction all execute against a wire they actually have to cross. A mocked port cannot prove
 * the two hops were ever addressed, and cannot produce the three answers the adapter must tell
 * apart (account found / account absent / lookup broken).
 *
 * Also stubs the OIDC token endpoint, because both REST clients carry an
 * [io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter] and would otherwise
 * dial the real realm to mint a bearer token. Same shape as settlement's
 * `BalanceServiceWireMockResource`.
 */
class VopDownstreamWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        server = WireMockServer(options().dynamicPort())
        server.start()
        stubToken()
        val base = server.baseUrl()
        return mapOf(
            "quarkus.rest-client.account-service.url" to base,
            "quarkus.rest-client.party-service.url" to base,
            "quarkus.oidc-client.enabled" to "true",
            "quarkus.oidc-client.auth-server-url" to base,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to "/token",
            "quarkus.oidc-client.client-id" to "openbank-vop",
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

        /** Drops all stubs and request journal, leaving only the token endpoint — call per journey. */
        fun reset() {
            server.resetRequests()
            server.resetMappings()
            stubToken()
        }

        /** How many times account-service was asked about [iban] — the @Retry fan-out proof. */
        fun accountRequestsFor(iban: String): Int =
            server.findRequestsMatching(getRequestedFor(urlEqualTo(accountPath(iban))).build()).requests.size

        fun accountNeverRequested(): Int = server.findAll(
            com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                com.github.tomakehurst.wiremock.matching.UrlPattern.ANY,
            ),
        ).count { it.url.startsWith("/api/v1/accounts/") }

        private fun accountPath(iban: String) = "/api/v1/accounts/iban/$iban"

        private fun stubToken() {
            server.stubFor(
                post(urlEqualTo("/token")).willReturn(
                    okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}"""),
                ),
            )
        }

        /** Hop 1 answers: the IBAN is ours, held by [partyId]. */
        fun stubAccountFound(iban: String, partyId: String) {
            server.stubFor(
                get(urlEqualTo(accountPath(iban))).willReturn(
                    okJson("""{"id":"11111111-1111-1111-1111-111111111111","partyId":"$partyId","status":"ACTIVE"}"""),
                ),
            )
        }

        /** Hop 1 answers: the account exists but carries no party link — no name is resolvable. */
        fun stubAccountWithoutParty(iban: String) {
            server.stubFor(
                get(urlEqualTo(accountPath(iban))).willReturn(
                    okJson("""{"id":"22222222-2222-2222-2222-222222222222","partyId":null,"status":"ACTIVE"}"""),
                ),
            )
        }

        /** Hop 1 answers 404: we hold no account for this IBAN — an answer, not a failure. */
        fun stubAccountNotFound(iban: String) {
            server.stubFor(get(urlEqualTo(accountPath(iban))).willReturn(notFound()))
        }

        /** Hop 1 is broken: every attempt (the retry fan-out included) gets a 5xx. */
        fun stubAccountUnavailable(iban: String) {
            server.stubFor(
                get(urlEqualTo(accountPath(iban))).willReturn(aResponse().withStatus(503)),
            )
        }

        /** Hop 2 answers: the party's authoritative name ([legalName] wins over [tradingName]). */
        fun stubPartyName(partyId: String, legalName: String? = null, tradingName: String? = null) {
            val legal = legalName?.let { "\"legalName\":\"$it\"" }
            val trading = tradingName?.let { "\"tradingName\":\"$it\"" }
            val fields = listOfNotNull(legal, trading).joinToString(",")
            server.stubFor(
                get(urlEqualTo("/api/v1/parties/$partyId")).willReturn(okJson("{$fields}")),
            )
        }

        /** Hop 2 answers 404: the party link dangles — still no name, still NO_DATA. */
        fun stubPartyNotFound(partyId: String) {
            server.stubFor(get(urlEqualTo("/api/v1/parties/$partyId")).willReturn(notFound()))
        }
    }
}
