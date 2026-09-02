// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetSocketAddress
import java.time.Clock
import java.util.UUID

/**
 * Consumer-driven contract for the DEBIT-AUTHORIZATION call — the one that decides whether a
 * delegate may move money out of somebody else's account (ADR-0232 D3/D5, issue #2990 AC9).
 *
 * ## Why this contract has to exist
 *
 * `customer-edge` reaches account-service through [UpstreamClient], a plain `java.net.http` client
 * with no generated stub and no `@Path` anywhere: the URL is assembled by hand inside
 * [CustomerEdgeResource.fetchDelegatedPaymentDecision]. Nothing in the edge's own test suite can
 * see a wrong path or a misspelled query parameter, because every one of those tests mocks
 * `UpstreamClient` and therefore only proves the edge agrees with itself. That is exactly the
 * defect of #2269 — finrep called `/api/v1/ledger/trial-balance`, a ledger route that has never
 * existed, and its unit tests were green against a mocked port for the whole life of the feature.
 *
 * The consequence here is worse than a broken screen. `fetchDelegatedPaymentDecision` returns
 * `null` on any non-200, and a null decision is a REFUSAL — so a wrong path does not throw, it
 * makes every delegated payment 403 forever, quietly and fail-closed. Nothing alerts on a feature
 * that is merely always refused.
 *
 * ## The asymmetry, which IS the test (#2290)
 *
 * The expected path and query below are **literals**. The outgoing request is produced by calling
 * the real production method against the Pact mock server — the edge builds the URL, the real
 * [UpstreamClient] sends it, with its real headers. Deriving BOTH sides from production would be
 * vacuous: expectation and request would move together and the test could never fail.
 *
 * The `amount`/`currency` parameters are part of the contract on purpose. The grant's
 * per-transaction ceiling is evaluated FROM the amount, so a pact that omitted it would pass just
 * as happily against a provider that ignores it — and a provider that ignores the amount is a
 * provider with no ceiling at all.
 *
 * Provider replay: `AccountPactFolderProviderVerificationTest` (`@PactFolder`, runs on every PR).
 * Its `@State` seed and the fixed UUIDs below must stay in step, and the broker twin
 * `AccountEventPactProviderVerificationTest` carries the same state handler.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class CustomerEdgeDelegatedPaymentPactConsumerTest {

    private companion object {
        // Fixed UUIDs — must match the @State seed in account-service's provider verification.
        const val ACCOUNT_ID = "22222222-3333-4444-8555-666666666666"
        const val GRANTOR_PARTY_ID = "33333333-4444-4555-8666-777777777777"
        const val DELEGATE_PARTY_ID = "44444444-5555-4666-8777-888888888888"
        const val GRANT_ID = "55555555-6666-4777-8888-999999999999"

        // Under the seeded grant's 5000.00 CZK per-transaction ceiling, so the decision is DELEGATED.
        const val AMOUNT = "1500.00"
        const val CURRENCY = "CZK"
    }

    /**
     * A loopback stand-in for Keycloak's token endpoint. [UpstreamClient] fetches a
     * client_credentials token before every call and swallows any failure into a 502 — which
     * `fetchDelegatedPaymentDecision` would then read as a refusal, and the pact interaction would
     * never be exercised at all. Stubbing the token is what lets the REAL client issue the REAL
     * request; nothing about the token is under contract here.
     */
    private lateinit var tokenServer: HttpServer

    @BeforeEach
    fun startTokenServer() {
        tokenServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/protocol/openid-connect/token") { exchange ->
                val body = """{"access_token":"pact-token","expires_in":300}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @AfterEach
    fun stopTokenServer() {
        tokenServer.stop(0)
    }

    private fun edgeResource(accountServiceBaseUrl: String): CustomerEdgeResource {
        val upstream = UpstreamClient().apply {
            tokenEndpointBase = "http://127.0.0.1:${tokenServer.address.port}"
            clientId = "openbank-edge"
            clientSecret = "pact"
            tlsTrustCertificateFile = java.util.Optional.empty()
            // The Pact mock server binds 127.0.0.1, which the production default already allows.
            allowedHostSuffixes = ".svc,127.0.0.1,localhost"
        }
        return CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            PaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            objectMapper = ObjectMapper()
            accountServiceUrl = accountServiceBaseUrl
        }
    }

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-account-service")
    fun delegatedPaymentAuthorizationPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account with an ACTIVE payment delegation to a known party exists")
        .uponReceiving("GET the delegated-payment authorization decision for an amount within the ceiling")
        // LITERAL, deliberately. The request side is reflected off production; if both came from
        // the same place this expectation could not disagree with anything.
        .path("/api/v1/accounts/$ACCOUNT_ID/delegation/payment-authorization")
        .query("partyId=$DELEGATE_PARTY_ID&amount=$AMOUNT&currency=$CURRENCY")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.booleanValue("authorized", true)
                o.stringValue("outcome", "DELEGATED")
                // The two fields that make the payment auditable AS delegated. A provider that
                // authorised without returning them would leave the audit chain unable to name
                // the grant, which is the whole point of the endpoint.
                o.stringValue("delegationId", GRANT_ID)
                o.stringValue("grantorPartyId", GRANTOR_PARTY_ID)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "delegatedPaymentAuthorizationPact")
    fun `the edge's own request wins an authorising decision naming the grant and the grantor`(
        mockServer: MockServer,
    ) {
        val decision = edgeResource(mockServer.getUrl()).fetchDelegatedPaymentDecision(
            accountId = UUID.fromString(ACCOUNT_ID),
            partyId = UUID.fromString(DELEGATE_PARTY_ID),
            amount = AMOUNT,
            currency = CURRENCY,
        )

        // A null here means the edge did not get a 200 — i.e. its request did not match the
        // contract — and the production code treats that as a refusal, silently.
        assertThat(decision)
            .describedAs("the edge's request must match the contracted path and query")
            .isNotNull()
        assertThat(decision!!.authorized).isTrue()
        assertThat(decision.outcome).isEqualTo("DELEGATED")
        assertThat(decision.delegationId).isEqualTo(UUID.fromString(GRANT_ID))
        assertThat(decision.grantorPartyId).isEqualTo(UUID.fromString(GRANTOR_PARTY_ID))
    }
}
