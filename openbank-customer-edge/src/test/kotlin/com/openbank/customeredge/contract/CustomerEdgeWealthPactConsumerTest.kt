// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArrayMinLike
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactDslRequestWithoutPath
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.dsl.PactDslWithState
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerHoldingsResource
import com.openbank.customeredge.infrastructure.rest.CustomerPartyResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetSocketAddress
import java.util.Optional
import java.util.UUID

/**
 * Consumer contract for every call customer-edge makes to wealth-service (#11966): the
 * `/customer/v1/holdings` routes, and the list read `GET /net-worth` composes its declared-holdings
 * branch from (`NetWorthComposer.declaredBranch` sends exactly the request of [listHoldings]).
 *
 * The real [CustomerHoldingsResource] and [UpstreamClient] drive every interaction, so the request
 * shape is the one production sends. Every expected path is a LITERAL: a path derived from the
 * client would move with it, and the test would stay green against a route that does not exist.
 *
 * By-id routes are two interactions each, because the edge reads the holding to prove ownership
 * before it acts. The 404 for an unknown holding is the adversarial case: it is what the edge's
 * ownership guard relies on to answer "not yours" without revealing that an id exists.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-wealth-service", pactVersion = PactSpecVersion.V3)
class CustomerEdgeWealthPactConsumerTest {
    private lateinit var tokenStub: HttpServer

    @BeforeEach
    fun startTokenStub() {
        tokenStub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/protocol/openid-connect/token") { exchange ->
                val bytes = """{"access_token":"pact-token","expires_in":300}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @AfterEach
    fun stopTokenStub() = tokenStub.stop(0)

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-wealth-service")
    fun listHoldings(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE)
        .uponReceiving("GET the declared holdings of the customer party")
        .path("/api/v1/holdings")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .body(newJsonArrayMinLike(1) { it.`object` { h -> holding(h, "ACTIVE") } }.build())
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-wealth-service")
    fun readHolding(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE).readById(HOLDING_ID, "ACTIVE")
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-wealth-service")
    fun declareHolding(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_HOLDING_STATE)
        .uponReceiving("POST a customer-declared holding for the customer party")
        .path("/api/v1/holdings")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "X-Customer-Party-Id" to PARTY_ID))
        .body(
            newJsonBody { b ->
                b.stringValue("holdingType", "REAL_ESTATE")
                b.stringValue("label", "Flat in Brno")
                b.`object`("valuation") { v ->
                    v.numberValue("amount", 6_500_000)
                    v.stringValue("currency", "CZK")
                    v.stringValue("valuedAt", "2026-01-15")
                    v.stringValue("source", "CUSTOMER_DECLARED")
                }
                b.numberValue("ownershipShare", 0.5)
                b.nullValue("externalReference")
            }.build(),
        )
        .willRespondWith()
        .status(201)
        .body(newJsonBody { h -> holding(h, "ACTIVE", anyId = true) }.build())
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-wealth-service")
    fun revalueHolding(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE).readById(HOLDING_ID, "ACTIVE")
        .given(ACTIVE_STATE)
        .uponReceiving("PUT a customer-declared revaluation of the holding")
        .path("/api/v1/holdings/$HOLDING_ID/valuation")
        .method("PUT")
        .headers(mapOf("Content-Type" to "application/json", "X-Customer-Party-Id" to PARTY_ID))
        .body(
            newJsonBody { b ->
                b.`object`("valuation") { v ->
                    v.numberValue("amount", 7_000_000)
                    v.stringValue("currency", "CZK")
                    v.stringValue("valuedAt", "2026-09-01")
                    v.stringValue("source", "CUSTOMER_DECLARED")
                }
            }.build(),
        )
        .willRespondWith()
        .status(200)
        .body(newJsonBody { h -> holding(h, "ACTIVE") }.build())
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-wealth-service")
    fun valuationHistory(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE).readById(HOLDING_ID, "ACTIVE")
        .given(ACTIVE_STATE)
        .uponReceiving("GET the valuation history of the holding")
        .path("/api/v1/holdings/$HOLDING_ID/valuations")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .body(
            newJsonArrayMinLike(1) {
                it.`object` { v ->
                    v.numberType("amount", 6_500_000)
                    v.stringMatcher("currency", "[A-Z]{3}", "CZK")
                    v.stringMatcher("valuedAt", DATE, "2026-01-15")
                    v.stringMatcher("source", SOURCES, "CUSTOMER_DECLARED")
                    v.stringType("recordedAt", "2026-09-01T10:00:00Z")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-wealth-service")
    fun withdrawHolding(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE).readById(HOLDING_ID, "ACTIVE")
        .given(ACTIVE_STATE)
        .uponReceiving("DELETE an active holding")
        .path("/api/v1/holdings/$HOLDING_ID")
        .method("DELETE")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .body(newJsonBody { h -> holding(h, "WITHDRAWN") }.build())
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-wealth-service")
    fun withdrawPledgedHolding(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(PLEDGED_STATE).readById(PLEDGED_HOLDING_ID, "PLEDGED")
        .given(PLEDGED_STATE)
        .uponReceiving("DELETE a holding pledged as lending collateral")
        .path("/api/v1/holdings/$PLEDGED_HOLDING_ID")
        .method("DELETE")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(409)
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-wealth-service")
    fun unknownHolding(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_HOLDING_STATE)
        .uponReceiving("GET a holding the bank does not hold")
        .path("/api/v1/holdings/$UNKNOWN_HOLDING_ID")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(404)
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-wealth-service")
    fun missingIdentity(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the declared holdings with no M2M identity")
        .path("/api/v1/holdings")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listHoldings")
    fun `the list read used by holdings and net worth matches the provider`(mockServer: MockServer) {
        val response = resource(mockServer).list()

        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)[0]["valuationSource"].asText()).isEqualTo("CUSTOMER_DECLARED")
    }

    @Test
    @PactTestFor(pactMethod = "readHolding")
    fun `reading an owned holding matches the provider`(mockServer: MockServer) {
        assertThat(resource(mockServer).get(HOLDING_ID).status).isEqualTo(200)
    }

    @Test
    @PactTestFor(pactMethod = "declareHolding")
    fun `declaring matches the provider`(mockServer: MockServer) {
        val response = resource(mockServer).declare(
            """{"holdingType":"REAL_ESTATE","label":"Flat in Brno","ownershipShare":"0.5",
               "valuation":{"amount":"6500000","currency":"CZK","valuedAt":"2026-01-15"}}""",
        )

        assertThat(response.status).isEqualTo(201)
    }

    @Test
    @PactTestFor(pactMethod = "revalueHolding")
    fun `revaluing an owned holding matches the provider`(mockServer: MockServer) {
        val response = resource(mockServer).revalue(
            HOLDING_ID,
            """{"valuation":{"amount":"7000000","currency":"CZK","valuedAt":"2026-09-01"}}""",
        )

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    @PactTestFor(pactMethod = "valuationHistory")
    fun `the valuation history of an owned holding matches the provider`(mockServer: MockServer) {
        val response = resource(mockServer).valuations(HOLDING_ID)

        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)[0]["valuationSource"].asText()).isEqualTo("CUSTOMER_DECLARED")
    }

    @Test
    @PactTestFor(pactMethod = "withdrawHolding")
    fun `withdrawing an owned holding matches the provider`(mockServer: MockServer) {
        val response = resource(mockServer).withdraw(HOLDING_ID)

        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)["status"].asText()).isEqualTo("WITHDRAWN")
    }

    @Test
    @PactTestFor(pactMethod = "withdrawPledgedHolding")
    fun `withdrawing a pledged holding is refused by the provider and locked at the edge`(mockServer: MockServer) {
        val response = resource(mockServer).withdraw(PLEDGED_HOLDING_ID)

        assertThat(response.status).isEqualTo(409)
        assertThat(json(response)["code"].asText()).isEqualTo("HOLDING_LOCKED")
    }

    @Test
    @PactTestFor(pactMethod = "unknownHolding")
    fun `an unknown holding is NOT_FOUND at the provider and at the edge`(mockServer: MockServer) {
        assertThat(resource(mockServer).get(UNKNOWN_HOLDING_ID).status).isEqualTo(404)
    }

    /**
     * The edge always sends its M2M token, so this one is driven with a bare request: the
     * contract it records is that wealth-service refuses a caller with no identity (ADR-0279 #3),
     * which is what makes the edge the only way a customer reaches a holding.
     */
    @Test
    @PactTestFor(pactMethod = "missingIdentity")
    fun `the provider answers 401 UNAUTHORIZED to a caller with no identity`(mockServer: MockServer) {
        val connection = java.net.URI("${mockServer.getUrl()}/api/v1/holdings").toURL()
            .openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("X-Customer-Party-Id", PARTY_ID)

        assertThat(connection.responseCode).isEqualTo(401)
    }

    private fun resource(mockServer: MockServer): CustomerHoldingsResource {
        val upstream = UpstreamClient().apply {
            tokenEndpointBase = "http://127.0.0.1:${tokenStub.address.port}"
            clientId = "openbank-customer-edge"
            clientSecret = "pact"
            tlsTrustCertificateFile = Optional.empty()
        }
        val parties = mockk<CustomerPartyResolver> { every { resolve(any()) } returns UUID.fromString(PARTY_ID) }
        return CustomerHoldingsResource(upstream, parties).apply { wealthServiceUrl = mockServer.getUrl() }
    }

    private fun json(response: Response) = ObjectMapper().readTree(response.entity as String)

    private fun PactDslWithState.readById(id: String, status: String) =
        uponReceiving("GET the holding $id to prove the customer owns it").readById(id, status)

    private fun PactDslRequestWithoutPath.readById(id: String, status: String) = path("/api/v1/holdings/$id")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .body(newJsonBody { h -> holding(h, status) }.build())

    private companion object {
        const val PARTY_ID = "11111111-1111-4111-8111-111111111111"
        const val HOLDING_ID = "22222222-2222-4222-8222-222222222222"
        const val PLEDGED_HOLDING_ID = "33333333-3333-4333-8333-333333333333"
        const val UNKNOWN_HOLDING_ID = "99999999-9999-4999-8999-999999999999"
        const val ACTIVE_STATE = "the customer party holds an active declared holding"
        const val PLEDGED_STATE = "the customer party holds a declared holding pledged as lending collateral"
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
        const val NO_HOLDING_STATE = "the customer party holds no declared holding"
        const val DATE = "\\d{4}-\\d{2}-\\d{2}"
        const val SOURCES = "CUSTOMER_DECLARED|EXPERT_APPRAISAL|MARKET_REFERENCE"

        /**
         * The fields the edge reads. `ownerPartyId` is the one the ownership guard compares, so the
         * provider must return the party it stored; the rest are matched by type.
         */
        fun holding(h: LambdaDslObject, status: String, anyId: Boolean = false) {
            if (anyId) h.uuid("holdingId") else h.uuid("holdingId", UUID.fromString(HOLDING_ID))
            h.uuid("ownerPartyId", UUID.fromString(PARTY_ID))
            h.stringType("holdingType", "REAL_ESTATE")
            h.booleanType("isLiability", false)
            h.stringType("label", "Flat in Brno")
            h.numberType("amount", 6_500_000)
            h.stringMatcher("currency", "[A-Z]{3}", "CZK")
            h.stringMatcher("valuedAt", DATE, "2026-01-15")
            h.stringMatcher("valuationSource", SOURCES, "CUSTOMER_DECLARED")
            h.numberType("ownershipShare", 0.5)
            h.numberType("attributableAmount", 3_250_000)
            h.integerType("valuationAgeDays", 261)
            h.stringValue("status", status)
            h.stringType("createdAt", "2026-09-01T10:00:00Z")
            h.stringType("updatedAt", "2026-09-01T10:00:00Z")
        }
    }
}
