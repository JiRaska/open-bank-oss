// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.util.UUID

/**
 * Stands in for every downstream trust boundary a statement journey crosses, over **real HTTP**:
 * account-service (account + currency pockets), party-service (holder name), transaction-service
 * (booked entries, paged), balance-service (point-in-time pocket balances) and document-service
 * (template list + non-persisting preview). A mocked port cannot prove the close actually fanned
 * out to four money-path services, nor that render-after-close replays the frozen snapshot WITHOUT
 * dialling them (the snapshot-purity journey asserts exactly that from the request journal).
 *
 * Also stubs the OIDC token endpoint TWICE — the default client and the NAMED `m2m` client
 * (#10486 batch 7: transaction-service's search rides `service-account-openbank-statement`) —
 * because every REST client carries an OIDC filter and would otherwise dial the real realm.
 * Same shape as settlement's `BalanceServiceWireMockResource`.
 */
class StatementDownstreamWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        server = WireMockServer(options().dynamicPort())
        server.start()
        stubToken()
        val base = server.baseUrl()
        return mapOf(
            "quarkus.rest-client.transaction-api.url" to base,
            "quarkus.rest-client.balance-api.url" to base,
            "quarkus.rest-client.account-api.url" to base,
            "quarkus.rest-client.party-api.url" to base,
            "quarkus.rest-client.document-api.url" to base,
            "quarkus.oidc-client.auth-server-url" to base,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to "/token",
            "quarkus.oidc-client.client-id" to "openbank-statement",
            "quarkus.oidc-client.credentials.secret" to "test-secret",
            "quarkus.oidc-client.grant.type" to "client",
            "quarkus.oidc-client.m2m.auth-server-url" to base,
            "quarkus.oidc-client.m2m.discovery-enabled" to "false",
            "quarkus.oidc-client.m2m.token-path" to "/token",
            "quarkus.oidc-client.m2m.client-id" to "openbank-statement",
            "quarkus.oidc-client.m2m.credentials.secret" to "test-secret",
            "quarkus.oidc-client.m2m.grant.type" to "client",
        )
    }

    override fun stop() {
        if (isStarted()) server.stop()
    }

    companion object {
        lateinit var server: WireMockServer

        fun isStarted() = ::server.isInitialized

        /** Drops all stubs and the request journal, leaving only the token endpoint — call per journey. */
        fun reset() {
            server.resetRequests()
            server.resetMappings()
            stubToken()
        }

        /** How many requests hit [path] since the last [reset] — the snapshot-purity proof reads this. */
        fun requestsTo(path: String): Int =
            server.findRequestsMatching(getRequestedFor(urlPathEqualTo(path)).build()).requests.size

        private fun accountPath(accountId: UUID) = "/api/v1/accounts/$accountId"

        private fun stubToken() {
            server.stubFor(
                post(urlEqualTo("/token")).willReturn(
                    okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}"""),
                ),
            )
        }

        /** account-service: the account exists, carrying its IBAN (accountNumber) and party link. */
        fun stubAccount(accountId: UUID, iban: String, partyId: UUID) {
            server.stubFor(
                get(urlEqualTo(accountPath(accountId))).willReturn(
                    okJson(
                        """{"id":"$accountId","accountNumber":"$iban","partyId":"$partyId",""" +
                            """"currencies":["CZK","EUR"]}""",
                    ),
                ),
            )
        }

        /** account-service: the currency pockets (ADR-0024) — only ACTIVE ones are closed. */
        fun stubPockets(accountId: UUID, vararg pockets: Pair<String, String>) {
            val body = pockets.joinToString(",") { (ccy, status) ->
                """{"currencyCode":"$ccy","status":"$status"}"""
            }
            server.stubFor(
                get(urlEqualTo("${accountPath(accountId)}/pockets")).willReturn(okJson("""{"pockets":[$body]}""")),
            )
        }

        /** party-service: the holder's legal name for the statement header. */
        fun stubParty(partyId: UUID, legalName: String) {
            server.stubFor(
                get(urlEqualTo("/api/v1/parties/$partyId")).willReturn(
                    okJson("""{"id":"$partyId","legalName":"$legalName"}"""),
                ),
            )
        }

        /**
         * transaction-service: the booked-entry search envelope for [accountId] in [from]..[to].
         * One short page (< 200) ends the adapter's pagination; [entriesJson] is the raw array.
         */
        fun stubTransactions(accountId: UUID, from: String, to: String, entriesJson: String) {
            val count = entriesJson.filter { it == '{' }.length
            server.stubFor(
                get(urlPathEqualTo("/api/v1/transactions/search"))
                    .withQueryParam("accountId", equalTo(accountId.toString()))
                    .withQueryParam("dateFrom", equalTo(from))
                    .withQueryParam("dateTo", equalTo(to))
                    .willReturn(okJson("""{"data":[$entriesJson],"count":$count,"limit":200,"offset":0}""")),
            )
        }

        /** balance-service: the point-in-time booked balance for the pocket as of [asOf]. */
        fun stubBalance(accountId: UUID, currency: String, asOf: String, booked: String) {
            server.stubFor(
                get(urlPathEqualTo("/api/v1/balances/$accountId/$currency"))
                    .withQueryParam("asOf", equalTo(asOf))
                    .willReturn(
                        okJson(
                            """{"accountId":"$accountId","currency":"$currency","bookedAmount":$booked,"availableAmount":$booked}""",
                        ),
                    ),
            )
        }

        /** document-service: the published template list carrying [code]'s bodyHtml. */
        fun stubDocumentTemplates(code: String, bodyHtml: String) {
            server.stubFor(
                get(urlPathEqualTo("/api/v1/documents/templates")).willReturn(
                    okJson(
                        """[{"code":"$code","version":"1","bodyHtml":"${bodyHtml.replace(
                            "\"",
                            "\\\"",
                        )}","status":"PUBLISHED"}]""",
                    ),
                ),
            )
        }

        /** document-service: the non-persisting preview merge. */
        fun stubDocumentPreview(renderedHtml: String) {
            server.stubFor(
                post(urlEqualTo("/api/v1/documents/templates/preview")).willReturn(
                    okJson("""{"renderedHtml":"${renderedHtml.replace("\"", "\\\"")}"}"""),
                ),
            )
        }

        /** balance-service is silent: every pocket-balance read answers 503. */
        fun stubBalanceUnavailable(accountId: UUID, currency: String) {
            server.stubFor(
                get(urlPathEqualTo("/api/v1/balances/$accountId/$currency")).willReturn(aResponse().withStatus(503)),
            )
        }
    }
}
